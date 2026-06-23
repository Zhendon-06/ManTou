# 馒头 App 可用 Tools

> **编译期自动生成，请勿手动修改**。
> 来源: app/src/main/java/com/hfad/mantou/tool/impl/
>
> ## 调用约定
> ```js
> // 1. 先判断是否在馒头 App 中
> if (window.MantouApp && window.MantouApp.isMantouApp && window.MantouApp.isMantouApp()) {
>     // 2. 调用 Tool 方法（同步返回 JSON 字符串）
>     var rawJson = window.MantouApp.<toolName>.<methodName>(...args);
>     var r = JSON.parse(rawJson);
>     if (r.success) { /* 用 r.data */ } else { /* 提示 r.error */ }
> }
> ```
>
> 所有方法都返回 JSON 字符串：
> `{"success": bool, "data": any, "error": string|null}`
>
> **重要**：参数只能传基本类型（String / Int / Long / Boolean / Double），
> 不能传 JS 对象、数组或函数。

---

## `alarm`

**描述**：调用系统闹钟应用：单次闹钟、重复闹钟、倒计时器，以及打开闹钟主界面

**使用场景**：网页里需要让用户在系统层面被叫醒/提醒；倒计时；早起提醒；周一到周五重复闹钟

### `window.MantouApp.alarm.alarmSet(hour: Int, minute: Int, label: String)` → String

跳转到系统闹钟 App，预填一个新的单次闹钟。用户在系统页面里点击保存才生效。

**参数**：
- `hour` (Int)：0-23
- `minute` (Int)：0-59
- `label` (String)：闹钟标签，可为空字符串

**返回**：是否成功唤起系统闹钟界面

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.alarm.alarmSet(7, 30, '晨练');
```

### `window.MantouApp.alarm.alarmSetWithDays(hour: Int, minute: Int, label: String, daysCsv: String)` → String

设置重复闹钟。daysCsv 用 1-7 表示周日到周六，逗号分隔；例如 '2,3,4,5,6' 表示周一到周五。

**参数**：
- `hour` (Int)：0-23
- `minute` (Int)：0-59
- `label` (String)：闹钟标签
- `daysCsv` (String)：1=周日,2=周一,...,7=周六；逗号分隔

**返回**：是否成功唤起重复闹钟设置界面

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.alarm.alarmSetWithDays(7, 0, '上班', '2,3,4,5,6');
```

### `window.MantouApp.alarm.alarmTimer(seconds: Int, label: String)` → String

跳转到系统闹钟，启动一个倒计时器。

**参数**：
- `seconds` (Int)：倒计时秒数，1-86400
- `label` (String)：计时标签，可为空字符串

**返回**：是否成功唤起倒计时器界面

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.alarm.alarmTimer(300, '面条计时');
```

### `window.MantouApp.alarm.alarmOpen()` → String

直接打开系统闹钟应用，不预填任何内容。

**返回**：是否成功打开闹钟应用

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.alarm.alarmOpen();
```

---

## `calendar`

**描述**：在系统日历里插入事件 / 全天日程，或直接打开日历到指定时间

**使用场景**：网页里要把一个事件写入用户的日历并在指定时间提醒；或导航到某天查看日程

### `window.MantouApp.calendar.calendarAdd(title: String, location: String, beginMillis: Long, endMillis: Long)` → String

唤起系统日历，预填一个新事件。用户在系统页面点击保存才生效。

**参数**：
- `title` (String)：事件标题
- `location` (String)：地点，可为空字符串
- `beginMillis` (Long)：开始时间戳（毫秒，UTC）
- `endMillis` (Long)：结束时间戳（毫秒，UTC）

**返回**：是否成功唤起日历事件编辑界面

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.calendar.calendarAdd('团队周会', '线上会议室 A', 1718352000000, 1718355600000);
```

### `window.MantouApp.calendar.calendarAddWithReminder(title: String, location: String, beginMillis: Long, endMillis: Long, reminderMinutes: Int)` → String

添加事件并预设提醒。reminderMinutes 表示事件开始前 N 分钟提醒（0=准点）。

**参数**：
- `title` (String)：事件标题
- `location` (String)：地点，可为空字符串
- `beginMillis` (Long)：开始时间戳（毫秒，UTC）
- `endMillis` (Long)：结束时间戳（毫秒，UTC）
- `reminderMinutes` (Int)：事件开始前 N 分钟提醒，0-10080

**返回**：是否成功唤起日历事件编辑界面

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.calendar.calendarAddWithReminder('体检', '市医院', 1718352000000, 1718355600000, 30);
```

### `window.MantouApp.calendar.calendarAddAllDay(title: String, beginMillis: Long)` → String

添加全天日程，beginMillis 表示当天 00:00（UTC），自动 +1 天。

**参数**：
- `title` (String)：事件标题
- `beginMillis` (Long)：当天起始时间戳（毫秒，UTC）

**返回**：是否成功唤起日历事件编辑界面

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.calendar.calendarAddAllDay('生日', 1718323200000);
```

### `window.MantouApp.calendar.calendarOpen(timeMillis: Long)` → String

打开系统日历到指定时间戳所在日。timeMillis<=0 时打开默认视图。

**参数**：
- `timeMillis` (Long)：目标时间戳（毫秒，UTC）；<=0 表示打开默认视图

**返回**：是否成功打开日历

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.calendar.calendarOpen(1718352000000);
```

---

## `camera`

**描述**：唤起系统相机拍照/录像，并可把拍到的照片回传给网页显示

**使用场景**：网页里需要让用户拍一张照片并显示在 HTML 页面中、录制一段视频，或直接打开系统相机

### `window.MantouApp.camera.cameraTakePhoto()` → String

打开系统相机拍照，拍完后会调用 window.MantouApp.onCameraPhoto(dataUrl, uri)；dataUrl 可直接赋给 img.src 在 HTML 页面显示。

**返回**：是否成功发起拍照；照片结果通过 JS 回调异步返回

```json
{"success": true, "data": {"launched": true, "callback": "window.MantouApp.onCameraPhoto"}, "error": null}
```

**调用示例**：
```js
window.MantouApp.onCameraPhoto = function(dataUrl){ document.querySelector('#preview').src = dataUrl; }; window.MantouApp.camera.cameraTakePhoto();
```

### `window.MantouApp.camera.cameraTakePhotoWithCallback(callbackName: String)` → String

打开系统相机拍照，拍完后调用指定 JS 回调。回调函数接收两个参数：dataUrl 和 uri；dataUrl 可直接赋给 img.src。

**参数**：
- `callbackName` (String)：全局 JS 回调函数名，例如 window.handlePhoto；为空则使用 window.MantouApp.onCameraPhoto

**返回**：是否成功发起拍照；照片结果通过 callbackName 指向的 JS 函数异步返回

```json
{"success": true, "data": {"launched": true, "callback": "window.handlePhoto"}, "error": null}
```

**调用示例**：
```js
window.handlePhoto = function(dataUrl, uri){ document.getElementById('preview').src = dataUrl; }; window.MantouApp.camera.cameraTakePhotoWithCallback('window.handlePhoto');
```

### `window.MantouApp.camera.cameraRecordVideo()` → String

打开系统相机进入录像模式。用户录完自动保存到系统相册。

**返回**：是否成功唤起录像

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.camera.cameraRecordVideo();
```

### `window.MantouApp.camera.cameraOpen()` → String

打开系统相机应用（默认界面，不强制拍照或录像）。

**返回**：是否成功打开相机

```json
{"success": true, "data": {"launched": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.camera.cameraOpen();
```

---

## `clipboard`

**描述**：读取和写入系统剪贴板

**使用场景**：复制链接/口令到剪贴板；从剪贴板读取用户已复制的文本

### `window.MantouApp.clipboard.clipboardRead()` → String

读取剪贴板里第一项的纯文本。无内容时返回空字符串。注意 Android 10+ 只有前台 App 能读。

**返回**：剪贴板文本，无内容时为空字符串

```json
{"success": true, "data": {"text": "hello"}, "error": null}
```

**调用示例**：
```js
var r = JSON.parse(window.MantouApp.clipboard.clipboardRead()); if (r.success) alert(r.data.text);
```

### `window.MantouApp.clipboard.clipboardWrite(text: String, label: String)` → String

把文本写入剪贴板。Android 13+ 系统会自动屏蔽 Toast，请配合 toast 自行提示用户。

**参数**：
- `text` (String)：要写入的文本
- `label` (String)：剪贴项的标签（系统通知里显示），可为空字符串

**返回**：操作是否成功

```json
{"success": true, "data": {"length": 19}, "error": null}
```

**调用示例**：
```js
window.MantouApp.clipboard.clipboardWrite('https://example.com', '邀请链接');
```

### `window.MantouApp.clipboard.clipboardClear()` → String

清空剪贴板。Android 9 以下不支持，会返回错误。

**返回**：操作是否成功

```json
{"success": true, "data": {"cleared": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.clipboard.clipboardClear();
```

---

## `flashlight`

**描述**：控制设备的闪光灯/手电筒

**使用场景**：应急照明、SOS 闪烁、亮度提示等

### `window.MantouApp.flashlight.flashlightOn()` → String

打开手电筒。设备无闪光灯时返回错误。

**返回**：操作是否成功

```json
{"success": true, "data": {"on": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.flashlight.flashlightOn();
```

### `window.MantouApp.flashlight.flashlightOff()` → String

关闭手电筒。

**返回**：操作是否成功

```json
{"success": true, "data": {"on": false}, "error": null}
```

**调用示例**：
```js
window.MantouApp.flashlight.flashlightOff();
```

### `window.MantouApp.flashlight.flashlightToggle()` → String

切换手电筒开关，返回切换后状态。

**返回**：切换后的状态

```json
{"success": true, "data": {"on": true}, "error": null}
```

**调用示例**：
```js
var r = JSON.parse(window.MantouApp.flashlight.flashlightToggle()); if (r.success) console.log('on=' + r.data.on);
```

---

## `toast`

**描述**：弹一个原生 Toast 提示，用于轻量级即时反馈

**使用场景**：网页里给用户操作完成 / 失败 / 复制成功等即时反馈；也用于调试桥连通性

### `window.MantouApp.toast.toastShow(message: String, longDuration: Boolean)` → String

弹一个 Toast，可指定时长。Toast.makeText 必须在主线程，内部已切回。

**参数**：
- `message` (String)：提示文本
- `longDuration` (Boolean)：true=LENGTH_LONG (约 3.5s)，false=LENGTH_SHORT (约 2s)

**返回**：是否成功调度 Toast

```json
{"success": true, "data": {"shown": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.toast.toastShow('保存成功', true);
```

### `window.MantouApp.toast.toastShort(message: String)` → String

弹一个短 Toast（约 2s）。是 toastShow(msg, false) 的便捷写法。

**参数**：
- `message` (String)：提示文本

**返回**：是否成功调度 Toast

```json
{"success": true, "data": {"shown": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.toast.toastShort('已复制');
```

### `window.MantouApp.toast.toastLong(message: String)` → String

弹一个长 Toast（约 3.5s）。是 toastShow(msg, true) 的便捷写法。

**参数**：
- `message` (String)：提示文本

**返回**：是否成功调度 Toast

```json
{"success": true, "data": {"shown": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.toast.toastLong('网络不稳，请稍后重试');
```

---

## `vibration`

**描述**：控制设备振动：单次振动、自定义模式振动、停止振动

**使用场景**：操作反馈、通知提醒、节奏模拟（如心跳、SOS）

### `window.MantouApp.vibration.vibrateOnce(durationMs: Int)` → String

振动一次，时长 durationMs 毫秒。

**参数**：
- `durationMs` (Int)：振动时长，1-10000 毫秒

**返回**：操作是否成功

```json
{"success": true, "data": {"durationMs": 200}, "error": null}
```

**调用示例**：
```js
window.MantouApp.vibration.vibrateOnce(200);
```

### `window.MantouApp.vibration.vibratePattern(patternCsv: String, repeatIndex: Int)` → String

按模式振动。patternCsv 是逗号分隔的毫秒序列，从「等待」开始，奇偶交替；如 '0,200,100,200' = 立即振 200ms，停 100ms，再振 200ms。repeatIndex>=0 表示从该下标循环，-1 不循环。

**参数**：
- `patternCsv` (String)：逗号分隔的毫秒序列，等待/振动交替
- `repeatIndex` (Int)：循环起点下标，-1 表示只播放一次

**返回**：操作是否成功

```json
{"success": true, "data": {"steps": 6}, "error": null}
```

**调用示例**：
```js
window.MantouApp.vibration.vibratePattern('0,100,50,100,50,300', -1);
```

### `window.MantouApp.vibration.vibrateCancel()` → String

立即停止振动。

**返回**：操作是否成功

```json
{"success": true, "data": {"cancelled": true}, "error": null}
```

**调用示例**：
```js
window.MantouApp.vibration.vibrateCancel();
```

---

