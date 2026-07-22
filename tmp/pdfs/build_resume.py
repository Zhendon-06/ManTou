from io import BytesIO
from pathlib import Path

from pypdf import PdfReader
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT, TA_RIGHT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    Flowable,
    Image,
    KeepTogether,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parents[2]
SOURCE_PDF = Path("/Users/lzd/Downloads/Android开发梁振东 (1).pdf")
OUTPUT_PDF = ROOT / "output/pdf/梁振东_Android开发_两页优化版.pdf"
PHOTO_PATH = ROOT / "tmp/pdfs/profile.png"
FONT_PATH = "/System/Library/Fonts/STHeiti Medium.ttc"

PAGE_WIDTH, PAGE_HEIGHT = A4
MARGIN_X = 16 * mm
MARGIN_TOP = 13 * mm
MARGIN_BOTTOM = 12 * mm
CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN_X

NAVY = colors.HexColor("#17324D")
ACCENT = colors.HexColor("#178A61")
TEXT = colors.HexColor("#28323C")
MUTED = colors.HexColor("#66727D")
LIGHT = colors.HexColor("#E4E9ED")
SOFT = colors.HexColor("#F3F6F7")
WHITE = colors.white


pdfmetrics.registerFont(TTFont("ResumeCN", FONT_PATH))
pdfmetrics.registerFont(TTFont("ResumeCNBold", FONT_PATH))
pdfmetrics.registerFontFamily(
    "ResumeCN",
    normal="ResumeCN",
    bold="ResumeCNBold",
    italic="ResumeCN",
    boldItalic="ResumeCNBold",
)


styles = {
    "name": ParagraphStyle(
        "name",
        fontName="ResumeCNBold",
        fontSize=24,
        leading=28,
        textColor=NAVY,
        spaceAfter=2,
    ),
    "target": ParagraphStyle(
        "target",
        fontName="ResumeCNBold",
        fontSize=10.5,
        leading=14,
        textColor=ACCENT,
        spaceAfter=4,
    ),
    "contact": ParagraphStyle(
        "contact",
        fontName="ResumeCN",
        fontSize=9,
        leading=13,
        textColor=MUTED,
    ),
    "company": ParagraphStyle(
        "company",
        fontName="ResumeCNBold",
        fontSize=11.2,
        leading=15,
        textColor=NAVY,
    ),
    "date": ParagraphStyle(
        "date",
        fontName="ResumeCN",
        fontSize=9.2,
        leading=13,
        alignment=TA_RIGHT,
        textColor=MUTED,
    ),
    "project": ParagraphStyle(
        "project",
        fontName="ResumeCNBold",
        fontSize=10.3,
        leading=14,
        textColor=TEXT,
        spaceAfter=1,
    ),
    "meta": ParagraphStyle(
        "meta",
        fontName="ResumeCN",
        fontSize=8.5,
        leading=12,
        textColor=MUTED,
        spaceAfter=4,
    ),
    "body": ParagraphStyle(
        "body",
        fontName="ResumeCN",
        fontSize=9.4,
        leading=13.6,
        textColor=TEXT,
        wordWrap="CJK",
    ),
    "bullet": ParagraphStyle(
        "bullet",
        fontName="ResumeCN",
        fontSize=9.4,
        leading=13.6,
        textColor=TEXT,
        leftIndent=10,
        firstLineIndent=-9,
        spaceAfter=2.2,
        wordWrap="CJK",
    ),
    "skill": ParagraphStyle(
        "skill",
        fontName="ResumeCN",
        fontSize=9.1,
        leading=13,
        textColor=TEXT,
        wordWrap="CJK",
    ),
    "footer": ParagraphStyle(
        "footer",
        fontName="ResumeCN",
        fontSize=7.5,
        leading=9,
        textColor=MUTED,
    ),
}


class SectionHeader(Flowable):
    def __init__(self, title: str):
        super().__init__()
        self.title = title
        self.height = 21

    def wrap(self, available_width, available_height):
        self.width = available_width
        return available_width, self.height

    def draw(self):
        canvas = self.canv
        canvas.setFillColor(ACCENT)
        canvas.roundRect(0, 3, 4, 14, 2, fill=1, stroke=0)
        canvas.setFillColor(NAVY)
        canvas.setFont("ResumeCNBold", 12.3)
        canvas.drawString(10, 4, self.title)
        title_width = pdfmetrics.stringWidth(self.title, "ResumeCNBold", 12.3)
        canvas.setStrokeColor(LIGHT)
        canvas.setLineWidth(0.7)
        canvas.line(18 + title_width, 8, self.width, 8)


def extract_photo():
    reader = PdfReader(str(SOURCE_PDF))
    image = reader.pages[0].images[0]
    PHOTO_PATH.write_bytes(image.data)


def bullet(text: str):
    return Paragraph(f"<font color='#178A61'>●</font>&nbsp; {text}", styles["bullet"])


def company_row(name: str, role: str, date: str):
    left = Paragraph(f"{name}&nbsp;&nbsp;<font color='#66727D' size='9'>{role}</font>", styles["company"])
    right = Paragraph(date, styles["date"])
    table = Table([[left, right]], colWidths=[CONTENT_WIDTH - 105, 105])
    table.setStyle(
        TableStyle(
            [
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("LEFTPADDING", (0, 0), (-1, -1), 0),
                ("RIGHTPADDING", (0, 0), (-1, -1), 0),
                ("TOPPADDING", (0, 0), (-1, -1), 0),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
            ]
        )
    )
    return table


def project_heading(title: str, tech: str, github: str | None = None):
    items = [Paragraph(title, styles["project"])]
    meta = tech
    if github:
        meta += f"　|　<a href='{github}' color='#178A61'>{github}</a>"
    items.append(Paragraph(meta, styles["meta"]))
    return items


def draw_page(canvas, doc):
    page_number = canvas.getPageNumber()
    canvas.saveState()
    if page_number > 1:
        canvas.setStrokeColor(LIGHT)
        canvas.setLineWidth(0.7)
        canvas.line(MARGIN_X, PAGE_HEIGHT - 9 * mm, PAGE_WIDTH - MARGIN_X, PAGE_HEIGHT - 9 * mm)
        canvas.setFont("ResumeCN", 8)
        canvas.setFillColor(MUTED)
        canvas.drawString(MARGIN_X, PAGE_HEIGHT - 7 * mm, "梁振东 | Android 开发实习生")
    canvas.setFont("ResumeCN", 7.5)
    canvas.setFillColor(MUTED)
    canvas.drawRightString(PAGE_WIDTH - MARGIN_X, 7 * mm, f"{page_number} / 2")
    canvas.restoreState()


def header():
    photo = Image(str(PHOTO_PATH), width=28 * mm, height=35 * mm)
    left = [
        Paragraph("梁振东", styles["name"]),
        Paragraph("Android 开发实习生　|　2028 届", styles["target"]),
        Paragraph("桂林电子科技大学 · 数据科学与大数据技术 · 本科", styles["contact"]),
        Paragraph(
            "lzd15933617871@163.com　·　"
            "<a href='https://github.com/Zhendon-06' color='#178A61'>github.com/Zhendon-06</a>",
            styles["contact"],
        ),
    ]
    table = Table([[left, photo]], colWidths=[CONTENT_WIDTH - 35 * mm, 35 * mm])
    table.setStyle(
        TableStyle(
            [
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 0),
                ("RIGHTPADDING", (0, 0), (-1, -1), 0),
                ("TOPPADDING", (0, 0), (-1, -1), 0),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
                ("ALIGN", (1, 0), (1, 0), "RIGHT"),
            ]
        )
    )
    return table


def build_story():
    story = [header(), Spacer(1, 8), SectionHeader("实习经历"), Spacer(1, 4)]

    story.extend(
        [
            company_row("北京万象智维有限公司", "Android / Flutter 开发实习生", "2026.04 - 至今"),
            Spacer(1, 3),
            Paragraph("Android 端侧智能体项目", styles["project"]),
            bullet("将页面理解链路由 VLM 重构为 OCR + LLM 语义解析，提升 XML 结构匹配稳定性，并显著降低视觉模型 Token 消耗与调用成本。"),
            bullet("参与端侧智能体从产品立项、核心功能研发到主流应用商店上线，负责开源版与闭源版需求及进度对齐，保障双版本技术路线一致。"),
            bullet("梳理 Android Agent 核心业务流程、架构设计与交付文档，沉淀可复用技术方案并支持团队协作。"),
            Spacer(1, 5),
            Paragraph("Notta Zenchord One 智能耳机语音交互项目", styles["project"]),
            Paragraph("智能耳机与 Android App 的设备唤醒、BLE 连接、后台录音及会话管理核心链路。", styles["meta"]),
            bullet("打通耳机开盖及盒体按键唤醒链路，整合 BLE 广播、厂商 SDK、ACL/Profile 连接监听和事件去重，实现应用拉起、设备就绪检测、自动进入会话并启动录音。"),
            bullet("重构录音会话架构，将音频采集下沉至 Foreground Service，支持息屏、后台、页面切换及音频打断后持续录音，并将单次会话内多段音频统一归档。"),
            bullet("建设蓝牙链路可观测性，封装蓝牙信号日志、媒体键接收与 MediaSession，记录系统广播和连接状态，提升固件及 Android 兼容问题定位效率。"),
            bullet("结合前台服务、电池优化白名单、自启动与 WorkManager 完善稳定性兜底，在 Activity 销毁、服务结束、任务移除及异常场景统一清理录音状态。"),
            Spacer(1, 7),
            SectionHeader("个人项目"),
            Spacer(1, 4),
        ]
    )

    story.extend(
        project_heading(
            "馒头 AI - Android 端侧智能体与一句话生成 WebApp",
            "Kotlin · SSE · Coroutines/Flow · WebView Runtime · Room · ONNX",
            "https://github.com/Zhendon-06/ManTou",
        )
    )
    story.extend(
        [
            bullet("设计 WebView Tool Bridge，将相机、闹钟、日历、剪贴板等 Android 原生能力封装为统一 Tool；基于 @MantouTool / @ToolMethod / @ToolReturns 与 Gradle 编译期扫描自动生成 API 文档并注入生成 Prompt。"),
            bullet("实现“一句话生成并持续迭代 WebApp”闭环，设计 PREPARING、WRITING、APPLYING、COMPLETED/ERROR 任务状态机；支持 HTML/DIFF 流式预览、全屏带行号源码查看，并通过 Room 持久化任务类型与源码路径，实现跨会话恢复。"),
            bullet("自研受限 Unified Diff 执行引擎，支持 SHA-256 乐观锁、多 Hunk、上下文偏移、CRLF/UTF-8 兼容与原子写入，并拦截路径穿越、符号链接及多文件补丁，保障本地 Workspace 安全。"),
            PageBreak(),
            SectionHeader("项目与其他经历"),
            Spacer(1, 4),
            Paragraph("馒头 AI（续）", styles["project"]),
            Paragraph("SSE · Coroutines/Flow · ONNX Runtime", styles["meta"]),
            bullet("实现多会话并发流式引擎，以 sessionId 隔离 StreamingSessionState；针对长耗时代码生成取消 SSE 固定 120s 读取超时，使用 trySendBlocking 保证慢消费者场景下 Chunk 可靠投递，并支持断线续写、主动停止及部分结果落库。"),
            bullet("接入端侧 ONNX Embedding，将意图路由重构为规则预判、本地语义分类和云端兜底，使意图识别耗时由约 3000ms 降至 5ms 内，改善首响应并减少不必要的云端请求。"),
            Spacer(1, 6),
        ]
    )

    story.extend(
        project_heading(
            "NBanaMusic - Android 本地音乐播放器",
            "Kotlin · MVVM · Room · Flow · Foreground Service · MediaSession",
            "https://github.com/Zhendon-06/NBanaMusic",
        )
    )
    story.extend(
        [
            bullet("构建 Foreground Service + MediaSession + 通知栏控制播放框架，支持后台播放、系统媒体交互及播放模式持久化。"),
            bullet("通过 ViewModel、LiveData/Flow 与播放层监听统一歌曲、进度、播放模式和队列状态，保证播放器页、底部播放栏与歌单页状态一致。"),
            bullet("基于 BottomSheet、RecyclerView 与 ItemTouchHelper 实现交互式播放队列，支持当前歌曲高亮、点击切歌、拖拽排序、删除及空队列兜底。"),
            bullet("实现本地音乐异步扫描、Room 歌单同步、逐字歌词高亮与点击定位，补齐收藏、播放、管理和分享闭环。"),
            Spacer(1, 6),
            SectionHeader("开源经历"),
            Spacer(1, 3),
            Paragraph("OpenOmniBot", styles["project"]),
            Paragraph(
                "<a href='https://github.com/omnimind-ai/OpenOmniBot' color='#178A61'>github.com/omnimind-ai/OpenOmniBot</a>",
                styles["meta"],
            ),
            bullet("参与 Flutter UI 问题修复，相关 PR 已合并至 main；解决 TextField 提交、Focus 收起与 Overlay 键盘监听冲突。"),
            bullet("使用 file_picker 替换原壁纸选择链路，修复部分场景下本地图片选择失效问题，提升跨平台兼容性。"),
            Spacer(1, 6),
            SectionHeader("校园经历"),
            Spacer(1, 3),
            bullet("担任校内创新基地软件部 Android 组组长，负责模块设计、需求拆分、开发协调与测试。"),
            Spacer(1, 6),
            SectionHeader("专业技能"),
            Spacer(1, 3),
        ]
    )

    skill_rows = [
        ("Android", "Activity / Fragment 生命周期 · View 绘制与事件分发 · Handler · Service · MediaSession"),
        ("架构与异步", "Kotlin Coroutines / Flow · MVVM · Room · Hilt · AIDL · WorkManager"),
        ("网络与调试", "OkHttp / Retrofit · SSE · ADB · ANR / 内存问题排查 · Git · Gradle"),
        ("AI 与跨端", "LLM Agent · ONNX Runtime · WebView JSBridge · Flutter 与原生交互 · Prompt 工程"),
    ]
    skill_table = Table(
        [[Paragraph(f"<b>{name}</b>", styles["skill"]), Paragraph(value, styles["skill"])] for name, value in skill_rows],
        colWidths=[68, CONTENT_WIDTH - 68],
    )
    skill_table.setStyle(
        TableStyle(
            [
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 0),
                ("RIGHTPADDING", (0, 0), (-1, -1), 0),
                ("TOPPADDING", (0, 0), (-1, -1), 1.5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 1.5),
                ("LINEBELOW", (0, 0), (-1, -2), 0.35, LIGHT),
            ]
        )
    )
    story.append(skill_table)
    return story


def build_pdf():
    extract_photo()
    OUTPUT_PDF.parent.mkdir(parents=True, exist_ok=True)
    doc = SimpleDocTemplate(
        str(OUTPUT_PDF),
        pagesize=A4,
        leftMargin=MARGIN_X,
        rightMargin=MARGIN_X,
        topMargin=MARGIN_TOP,
        bottomMargin=MARGIN_BOTTOM,
        title="梁振东 - Android 开发简历",
        author="梁振东",
        subject="Android 开发实习生简历",
    )
    doc.build(build_story(), onFirstPage=draw_page, onLaterPages=draw_page)


if __name__ == "__main__":
    build_pdf()
