# 2.0.0-rc.1 (20)

- 包名改为 io.github.katiusu.netpilot（新应用身份，旧版本数据与设置不自动迁移；Tasker 任务与快捷设置磁贴需按新包名重新绑定）。
- Miuix 升到 0.9.5-beta02，接入 miuix-material / miuix-glass / miuix-glass-icons。
- UI 按 MiuixExample 模板重组：全局搜索（底栏搜索钮 + 结果浮层）、玻璃底栏/液态玻璃底栏、AppPageScaffold/AppSectionCard/AppArrowPreference 等统一骨架。
- 首页、功能页（Hook 选项）、设置页、关于页、日志页、监控页、许可页统一到新骨架；关于页信息全部改为本项目内容。
- 新增 GitHub Actions 构建（.github/workflows/build.yml）。
