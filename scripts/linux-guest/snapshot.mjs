import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { gzipSync } from "node:zlib";

const [bootDir, assetDir, emulatorModule] = process.argv.slice(2);
const { V86 } = await import(pathToFileURL(resolve(emulatorModule)));
const bytes = (directory, name) => Uint8Array.from(readFileSync(resolve(directory, name))).buffer;
const machine = new V86({
    wasm_path: resolve(assetDir, "v86.wasm"),
    bios: { buffer: bytes(assetDir, "seabios.bin") },
    vga_bios: { buffer: bytes(assetDir, "vgabios.bin") },
    bzimage: { buffer: bytes(bootDir, "kernel.bin") },
    initrd: { buffer: bytes(bootDir, "initramfs.bin") },
    cmdline: "console=ttyS0 tsc=reliable random.trust_cpu=on",
    memory_size: 192 * 1024 * 1024,
    filesystem: {},
    autostart: false
});

let timeout;
try {
    let tail = "";
    await new Promise((ready, reject) => {
        timeout = setTimeout(
            () => reject(new Error("Timed out preparing the Linux snapshot: " + tail.trim())),
            120_000
        );
        machine.add_listener("serial0-output-byte", (byte) => {
            tail = (tail + String.fromCharCode(byte)).slice(-512);
            if (tail.includes("MANTOU_GUEST_READY")) ready();
            if (tail.includes("MANTOU_BOOT_ERROR")) reject(new Error(tail.trim()));
        });
        machine.add_listener("emulator-ready", () => machine.run());
    });
    await machine.stop();
    const state = await machine.save_state();
    writeFileSync(resolve(assetDir, "state.bin"), gzipSync(new Uint8Array(state), { level: 6 }));
} finally {
    clearTimeout(timeout);
    await machine.destroy();
}
