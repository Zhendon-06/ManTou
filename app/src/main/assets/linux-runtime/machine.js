"use strict";

window.addEventListener("message", async (event) => {
    const port = event.ports[0];
    if (!port) return;
    const debug = event.data === "run-debug";
    const reportError = (error) => port.postMessage("!" + String(error).slice(0, 400));
    window.addEventListener("unhandledrejection", (failure) => reportError(failure.reason), { once: true });
    let machine;
    try {
        const [jobResponse, initResponse] = await Promise.all([
            fetch("/job.json", { cache: "no-store" }),
            fetch("/bin/state.bin")
        ]);
        if (!jobResponse.ok || !initResponse.ok) throw new Error("无法读取 Linux 任务或系统文件");
        const job = new Uint8Array(await jobResponse.arrayBuffer());
        const state = await new Response(
            initResponse.body.pipeThrough(new DecompressionStream("gzip"))
        ).arrayBuffer();
        machine = new V86({
            wasm_path: "/bin/v86.wasm",
            bios: { url: "/bin/seabios.bin" },
            vga_bios: { url: "/bin/vgabios.bin" },
            initial_state: { buffer: state },
            memory_size: 192 * 1024 * 1024,
            filesystem: {},
            disable_keyboard: true,
            disable_mouse: true,
            disable_speaker: true,
            autostart: false
        });

        await new Promise((resolve) => machine.add_listener("emulator-ready", resolve));
        await machine.create_file("job.json", job);
        const completed = new Promise((resolve, reject) => {
            let tail = "";
            machine.add_listener("serial0-output-byte", (byte) => {
                tail = (tail + String.fromCharCode(byte)).slice(-96);
                if (debug && byte === 10) console.debug("Linux serial:", tail.trimEnd());
                if (tail.includes("MANTOU_JOB_DONE")) resolve();
                if (tail.includes("MANTOU_BOOT_ERROR")) reject(new Error("Linux 来宾无法初始化"));
            });
        });
        machine.run();
        machine.serial0_send("run\n");
        await completed;
        await machine.stop();
        const result = await machine.read_file("result.json");
        if (result.length > 12 * 1024 * 1024) throw new Error("Linux 返回数据超出上限");
        const text = new TextDecoder("utf-8", { fatal: true }).decode(result);
        for (let offset = 0; offset < text.length; offset += 32768) {
            port.postMessage("+" + text.slice(offset, offset + 32768));
        }
        port.postMessage("done");
    } catch (error) {
        reportError(error);
    } finally {
        if (machine) await machine.destroy();
    }
}, { once: true });
