# Pharmacy-In-Android

一个本地离线的安卓药品管理小程序（示例项目），核心功能：

- 拍照录入药品图片
- 使用本地 OCR 提取药品文字信息（药品名称、保质期、功效等）
- 手工新增/修改药品信息
- 余量记录与余量统计（总余量、低余量数量）
- 本地数据库保存，支持按名称/功效/备注搜索

## 数据字段

- 药品名称（必填）
- 保质期（可选）
- 主要功效（可选）
- 余量（可选）
- 备注（可选）
- 药品照片（可选）

## 技术实现

- Jetpack Compose（UI）
- Room（本地数据库）
- ML Kit 中文文字识别（本地 OCR）
- FileProvider + 相机拍照

## APK Pipeline（GitHub Actions）

仓库已配置自动构建 APK 的流水线：

- Workflow 文件：`/home/runner/work/Pharmacy-In-Android/Pharmacy-In-Android/.github/workflows/build-apk.yml`
- 触发方式：
  - 手动触发（`workflow_dispatch`）
  - 推送到 `main/master`
  - Pull Request
- 产物：`app-debug.apk`

## 下载 APK 到本地使用

1. 打开仓库的 **Actions** 页面。
2. 选择 **Build APK** 工作流并进入某次成功的运行。
3. 在页面底部 **Artifacts** 下载 `pharmacy-debug-apk`。
4. 解压后得到 `app-debug.apk`，传到手机安装即可。

如需命令行安装（已连接 adb）：

```bash
adb install -r app-debug.apk
```

## 说明

当前仓库提供的是基础实现骨架，便于后续继续扩展（比如提醒、分类、导出备份、多图片等）。
