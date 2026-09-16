# batch-shot-camera（批次拍摄相机）

> 本项目基于 [Pangu-Immortal/FilterCamera](https://github.com/Pangu-Immortal/FilterCamera)（MIT License）改造，
> 在原项目基础上增加了**批次拍摄 / 工作模式 / 信息水印 / 拍摄清单**等面向现场记录的能力。
> 原始版权与许可声明保留在 [LICENSE](LICENSE) 中，请一并遵守。

---

## 这个项目解决什么问题

现场作业（设备上架、巡检、盘点）往往需要**按固定顺序**给一批对象拍照，并且每张照片要带上
"在哪、什么时候、谁拍的、拍的是哪一项"这些信息。普通相机做不到这三件事，这个项目把它们串起来了：

```
选批次 → 选/自动取下一个名字 → 拍摄 → 照片按预设名字命名 + 自动归入批次目录 + 叠加信息水印
```

## 在原项目基础上新增的能力

| 能力 | 说明 |
|---|---|
| **批次拍摄** | 选一个批次，之后每张照片按批次规则命名（`前缀_序号.jpg`）并归入 `Pictures/{目录}/` |
| **工作模式** | 名字不靠序号，而是**按预设名字列表顺序**取用，如 `设备上架_拖车.jpg`、`设备上架_sn号.jpg` |
| **轮次归档** | 名字拍完后**自动进入下一轮**，文件落到 `目录/日期/第N轮/`，同一套名字可以反复拍而不冲突 |
| **逐项拍摄状态** | 支持跳拍（先拍第 3 项，前两项保持"待补拍"）、回头补拍、点已拍项=重拍（自动删掉原照片） |
| **信息水印** | 多行水印面板：经度 / 纬度 / 地址 / 时间 / 天气 / 备注，字段可开关、字号可调、长文本自动折行 |
| **拍摄清单** | 一眼看出还差哪几项没拍，点某一项即从该项开始 |
| **导出清单 CSV** | 导出批次照片清单到「文档」目录，便于交接核对 |
| **完整设置接线** | 原项目中若干"界面能改但不生效"的设置（照片质量、保存位置、默认滤镜/美颜/HDR、网格、镜像、自动保存等）已全部接通 |

## 构建

```bash
# 环境：JDK 21 + Android SDK Platform 36 + NDK 27.0.12077973 + CMake 3.22.1
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew :app:assembleDebug          # 调试包
./gradlew :app:assembleRelease        # 正式包（未签名）
```

ABI 已收窄为 **仅 arm64-v8a**（`app/build.gradle.kts` 与 `core/filter/build.gradle.kts` 两处），
需要跑模拟器时把 `"x86_64"` 加回去。

### ⚠️ 已知问题：原生源码缺失

`core/filter/src/main/cpp/CMakeLists.txt` 引用了 `gpuimage/yuv-decoder.c`，但**该文件不在本仓库中**
（上游仓库同样缺失）。当前的处理方式是：

- `CMakeLists.txt` 中该目标改为**按文件是否存在选择性编译**
- `core/filter/build.gradle.kts` 相应放开了对 gpuimage AAR 自带预编译库的剥离，缺文件时改用官方预编译库

因此**本项目可以正常构建**，代价是 `libyuv-decoder.so` 退回 4KB 页对齐版本
（该库在本 App 的运行路径中不会被加载）。若你能拿到原作者那份源码，放回该路径即可自动切回 16KB 对齐版本。

## 与原始仓库的差异范围

改动集中在 `app/src/main/kotlin/.../{domain,data,presentation}`、`core/filter/.../WatermarkRenderer.kt`
以及构建配置；相机的原有能力（CameraX 预览/拍照/录像、滤镜、美颜、人像、夜景、专业模式、
文档扫描、延时摄影、相册与编辑器）保持原样。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

单测覆盖批次命名与状态机、水印渲染（真实 Canvas 出像素 + 溢出断言）、YUV 转 NV12/NV21 字节一致性。
UI 层与端到端流程暂无自动化测试。

---

# 原始项目说明（以下内容来自上游 FilterCamera）

# FilterCamera 🎬

<div align="center">

![萌萌计数器](https://count.getloli.com/get/@FilterCamera?theme=rule34)

</div>

<p align="center">
  <b>🌟 如果觉得有帮助，请点击 <a href="https://github.com/Pangu-Immortal/FilterCamera/stargazers">Star</a> 支持一下，关注不迷路！🌟</b>
</p>

> 实时滤镜相机 - 包含美颜等72种实时滤镜，支持拍照、录像功能

## 项目概览

FilterCamera 是一款 Android 实时滤镜相机应用，提供：

### 📸 拍摄功能
- ✅ 高清拍照和视频录制
- ✅ 前后摄像头切换
- ✅ 定时拍照（3秒/5秒/10秒）
- ✅ 闪光灯控制（自动/开/关/常亮）
- ✅ 触摸对焦（带动画指示器）
- ✅ 双指缩放变焦

### 🎨 滤镜系统
- ✅ 72种实时滤镜效果（风格、特效、Instagram、水印）
- ✅ 22种GPU特效（扭曲、像素化、艺术、模糊等）
- ✅ 20种Instagram经典滤镜（独立分类）
- ✅ 10种水印相机（时间戳、地理位置、天气、设备信息等）
- ✅ 滤镜强度实时调节

### 💄 美颜功能
- ✅ 10级美颜强度调节（C++ Native加速）
- ✅ 磨皮/美白/瘦脸
- ✅ ML Kit人脸检测驱动

### 📷 专业模式 (Pro)
- ✅ ISO手动控制（100-6400）
- ✅ 快门速度控制（1/8000s - 1s）
- ✅ 白平衡调节
- ✅ 手动对焦
- ✅ 曝光补偿（±2EV）

### 🌙 高级拍摄模式
- ✅ **HDR模式** - CameraX Extensions硬件加速 + 软件曝光融合降级
- ✅ **夜景模式** - 多帧合成 + 时域/空域降噪
- ✅ **人像模式** - ML Kit人像分割 + 可调节背景虚化
- ✅ **延时摄影** - 可配置间隔/帧率，MediaCodec视频合成
- ✅ **人脸追踪对焦** - ML Kit人脸检测 + 自动追焦

### 📄 文档扫描
- ✅ **基础模式** - Sobel边缘检测 + 透视校正 + 5种滤镜
- ✅ **高级模式** - ML Kit Document Scanner（阴影去除 + PDF导出 + 多页扫描）

### 🖼️ 后期处理
- ✅ **图片编辑器** - 裁剪/旋转/翻转/调整/滤镜后期
- ✅ **相册管理** - 网格浏览/搜索/多选删除

### 📐 辅助工具
- ✅ 网格线（三分法/黄金分割/方形）
- ✅ 水平仪（陀螺仪驱动）

**不包含**：云端同步、社交分享

## 架构设计

### 技术栈
| 组件 | 技术选型 | 版本 |
|-----|---------|------|
| 语言 | Kotlin | 2.1.0 |
| 最低SDK | Android 7 (API 24) | - |
| 目标SDK | Android 16 (API 36) | - |
| UI框架 | Jetpack Compose | BOM 2024.12.01 |
| 相机 | CameraX | 1.4.1 |
| 依赖注入 | Hilt | 2.54 |
| 异步 | Kotlin Coroutines/Flow | 1.10.1 |
| Native | CMake + NDK | C++17 |
| 构建 | AGP + Kotlin DSL | 8.13.1 |

### 架构模式：Clean Architecture

```
app/
├── domain/           # 领域层 - 业务核心
│   ├── model/        # 实体：FilterType, BeautyLevel, CameraState, EditState
│   ├── repository/   # 仓库接口：ICameraRepository, IFilterRepository, IMediaRepository
│   └── usecase/      # 用例：TakePhotoUseCase, ApplyFilterUseCase
│
├── data/             # 数据层 - 数据来源
│   ├── repository/   # 仓库实现：CameraRepositoryImpl, MediaRepositoryImpl
│   └── processor/    # 处理器：BeautyProcessor, FaceDetectionProcessor
│
├── presentation/     # 表现层 - UI
│   ├── camera/       # 相机页面：CameraScreen, CameraViewModel
│   ├── gallery/      # 相册页面：GalleryScreen, GalleryViewModel（含搜索功能）
│   ├── edit/         # 编辑页面：EditScreen, EditViewModel（裁剪/调整/滤镜）
│   ├── settings/     # 设置页面：SettingsScreen, SettingsViewModel
│   ├── navigation/   # 导航：NavGraph, Screen
│   └── common/theme/ # 主题：Color, Type, Theme
│
└── di/               # 依赖注入：AppModule, RepositoryModule
```

### 设计决策
1. **CameraX替代Camera API** - 简化生命周期管理，内置预览/拍照/录像支持
2. **Hilt依赖注入** - 解耦组件，便于测试和维护
3. **Flow状态管理** - 响应式UI更新，单向数据流
4. **MediaStore存储** - 适配Scoped Storage，无需MANAGE_EXTERNAL_STORAGE权限

## 工程结构

```
FilterCamera/
├── app/                          # 主应用模块
│   └── src/main/
│       ├── kotlin/com/qihao/filtercamera/
│       │   ├── FilterCameraApp.kt    # Application入口
│       │   ├── MainActivity.kt       # 主Activity
│       │   ├── domain/               # 领域层
│       │   ├── data/                 # 数据层
│       │   ├── presentation/         # 表现层
│       │   └── di/                   # 依赖注入
│       └── res/                      # 资源文件
│
├── core/
│   ├── filter/                   # 滤镜核心模块
│   │   └── src/main/cpp/         # Native美颜算法
│   │       ├── MagicJni.cpp      # JNI接口
│   │       ├── beautify/         # 美颜算法
│   │       └── bitmap/           # 位图操作
│   └── common/                   # 公共工具模块
│
├── gradle/
│   └── libs.versions.toml        # 版本目录
│
├── build.gradle.kts              # 根构建脚本
├── settings.gradle.kts           # 设置脚本
└── gradle.properties             # Gradle属性
```

## 运行环境

### 系统要求
- **操作系统**：macOS / Windows / Linux
- **JDK**：21+
- **Android Studio**：Ladybug 2024.2.1+
- **Gradle**：8.13

### 设备要求
- **最低版本**：Android 7.0 (API 24)
- **目标版本**：Android 16 (API 36)
- **架构支持**：arm64-v8a, armeabi-v7a, x86_64（模拟器）
- **硬件**：支持相机设备

## 从零搭建指南

### 1. 环境准备
```bash
# 安装JDK 21
brew install openjdk@21

# 设置JAVA_HOME
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# 验证版本
java -version
```

### 2. 克隆项目
```bash
git clone https://github.com/Pangu-Immortal/FilterCamera.git
cd FilterCamera
```

### 3. 安装NDK（首次构建自动下载）
```bash
# 或手动指定NDK路径
# 在local.properties中添加：
# ndk.dir=/path/to/android-ndk
```

### 4. 构建项目
```bash
# Debug构建
./gradlew assembleDebug

# Release构建
./gradlew assembleRelease
```

### 5. 安装到设备
```bash
./gradlew installDebug
```

## 快速启动

### 环境检查
```bash
# 检查Java版本
java -version
# 期望输出：openjdk version "21.x.x"

# 检查Gradle版本
./gradlew --version
# 期望输出：Gradle 8.13
```

### 构建命令
```bash
# 清理并构建
./gradlew clean assembleDebug

# 仅编译（不打包）
./gradlew compileDebugKotlin

# 运行Lint检查
./gradlew lint
```

### 常见问题

**Q: 构建报错 "SDK location not found"**
```bash
# 创建local.properties，添加SDK路径
echo "sdk.dir=/Users/$(whoami)/Library/Android/sdk" > local.properties
```

**Q: NDK编译失败**
```bash
# 确保安装了CMake和NDK
# Android Studio -> SDK Manager -> SDK Tools -> 勾选CMake和NDK
```

**Q: 内存不足OOM**
```bash
# 增加Gradle堆内存（已配置4GB）
# 如需更多，编辑gradle.properties：
# org.gradle.jvmargs=-Xmx8192m
```

## 核心流程

### 1. 相机预览流程
```
用户打开App
  → MainActivity.onCreate()
  → CameraScreen Composable加载
  → 检查Camera权限
  → CameraRepositoryImpl.bindCamera()
  → CameraX Preview绑定到PreviewView
  → 实时预览显示
```

### 2. 拍照流程
```
用户点击拍照按钮
  → CameraViewModel.takePhoto()
  → TakePhotoUseCase.invoke()
  → CameraRepositoryImpl.takePhoto() [CameraX ImageCapture]
  → MediaRepositoryImpl.savePhoto() [MediaStore API]
  → 返回Uri，显示Toast
```

### 3. 滤镜切换流程
```
用户选择滤镜
  → CameraViewModel.selectFilter()
  → ApplyFilterUseCase.invoke()
  → FilterRepositoryImpl.setCurrentFilter()
  → CameraRepositoryImpl.applyFilter()
  → OpenGL渲染滤镜效果
  → 预览实时更新
```

### 4. 图片编辑流程
```
用户从相册进入编辑
  → GalleryScreen点击编辑按钮
  → 导航到EditScreen(imageUri)
  → EditViewModel.loadImage()
  → MediaRepositoryImpl.loadBitmap()（含EXIF方向处理）
  → GPUImage应用调整参数（亮度/对比度/饱和度/锐度/色温/暗角/高光/阴影）
  → Matrix应用变换（旋转/翻转）
  → FilterRepositoryImpl.applyFilter()（后期滤镜）
  → 预览实时更新
  → 保存：MediaRepositoryImpl.savePhoto()
```

### 5. 相册搜索流程
```
用户点击搜索图标
  → GalleryViewModel.setSearchActive(true)
  → 显示SearchTopBar
  → 用户输入关键词
  → GalleryViewModel.search(query)
  → 过滤mediaFiles（文件名包含关键词）
  → 显示filteredMediaFiles
```

## 技术债与风险

### Phase 10 功能扩展（2026-01-19 完成）
| 新功能 | 说明 |
|-------|------|
| 图片编辑器 | EditScreen + EditViewModel，支持裁剪/旋转/翻转/8种调整参数/滤镜后期 |
| 相册搜索 | GalleryScreen 文件名搜索功能，实时过滤 |
| 定时拍照 | 3秒/5秒/10秒倒计时，切换相机/模式自动取消 |
| Pro模式 | ISO/快门/白平衡/对焦手动控制 |
| 滤镜强度 | FilterIntensitySlider 0-100%强度调节 |

### Phase 9 技术债修复（2026-01-19 完成）
| 修复项 | 说明 |
|-------|------|
| JNI异常处理 | SafeMagicJni 安全封装，美颜失败优雅降级 |
| 帧缓冲优化 | FrameRingBuffer + 跳帧策略，消除ANR风险 |
| ViewModel拆分 | ProModeState + FilterSelectorState 状态类 |
| 滤镜注册表 | FilterRegistry 替代大型switch语句 |
| x86_64支持 | 模拟器可运行Native代码 |

### 已知限制
| 项目 | 说明 | 建议 |
|-----|------|-----|
| 滤镜预览缩略图 | 尚未实现OpenGL离屏渲染 | 后续版本完善 |
| 相机绑定 | CameraScreen中需要手动绑定 | 封装到CompositionLocal |
| ProGuard规则 | 部分模块缺少consumer-rules.pro | 创建空文件 |

### 不建议修改的区域
- `core/filter/src/main/cpp/` - Native美颜算法，修改需C++经验
- `libs.versions.toml` - 版本依赖已验证，升级需全面测试

## 滤镜效果列表（72种）

### 风格滤镜（19种）- GPU LUT
| 滤镜名 | 效果描述 |
|-------|---------|
| 童话、日出、日落、白猫、黑猫 | 色调风格化 |
| 美白、健康、甜蜜、浪漫、樱花 | 人像美化 |
| 温暖、复古、怀旧、平静、拿铁 | 氛围渲染 |
| 柔和、清凉、翡翠、常青 | 自然色调 |

### 特效滤镜（22种）- GPU Fragment Shader
| 分类 | 滤镜名 | 技术原理 |
|-----|-------|---------|
| **原有** | 蜡笔、素描 | 边缘检测+颜色量化 |
| **扭曲类** | 漩涡、鱼眼、捏缩、拉伸、玻璃球 | 极坐标/径向UV变形 |
| **像素化** | 像素化、半色调、交叉线、波点、马赛克 | 采样/网格映射 |
| **艺术类** | 油画、色调分离、浮雕、卡通、平滑卡通 | Kuwahara滤波/Sobel边缘 |
| **模糊类** | 移轴、动态模糊、缩放模糊、暗角、边缘检测 | 高斯/方向模糊 |

### Instagram风格（20种）- GPU LUT（独立分类）
| 滤镜 | 风格描述 |
|-----|---------|
| Amaro、Brannan、Brooklyn | 曝光柔化、金属灰、淡褪色 |
| Earlybird、Freud、Hefe | 复古黄棕、冷蓝紫、高对比 |
| Hudson、Inkwell、Kevin | 冷阴影、经典黑白、黄绿调 |
| Lomo、1977、Nashville | 胶片风格、复古、温暖粉 |
| Pixar、Rise、Sierra | 动画风、柔和暖、柔和对比 |
| Sutro、Toaster、Valencia | 紫褐色、老照片、褪色暖 |
| Walden、X-Pro II | 黄色增强、高对比暗角 |

### 水印相机（10种）- Canvas绘制
| 水印类型 | 功能描述 | 参考设计 |
|---------|---------|---------|
| 时间戳 | 日期+时间 | 通用相机 |
| 地理位置 | GPS位置+详细地址 | 小米/华为 |
| 天气 | 当前天气信息 | vivo/OPPO |
| 地图 | 地图截图+位置标记 | GPS Map Camera |
| 经纬度 | GPS坐标显示 | 专业相机 |
| 海拔 | 海拔高度信息 | 户外相机 |
| 设备信息 | 设备型号+镜头参数 | 徕卡水印风格 |
| 指南针 | 方向指示 | 户外相机 |
| 自定义 | 用户自定义文字 | 通用功能 |
| 日期 | 仅日期（不含时间） | 简洁模式 |

### 图像调整（8种）- GPU Shader参数
亮度、对比度、饱和度、锐度、色温、暗角、高光、阴影

## 样张展示

| 原图 | 童话 | 复古 |
|-----|------|-----|
| ![原图](Screenshot_1.png) | ![童话](Screenshot_2.png) | ![复古](Screenshot_3.png) |

## 开源引用

| 库 | 用途 | 链接 |
|---|------|------|
| CameraX | 相机框架 | https://developer.android.com/training/camerax |
| CameraX Extensions | HDR/夜景模式 | https://developer.android.com/training/camerax/extensions |
| Hilt | 依赖注入 | https://dagger.dev/hilt/ |
| Jetpack Compose | UI框架 | https://developer.android.com/jetpack/compose |
| Accompanist | 权限处理 | https://google.github.io/accompanist/ |
| Coil | 图片加载（相册） | https://coil-kt.github.io/coil/ |
| android-gpuimage | 滤镜渲染+图片编辑 | https://github.com/cats-oss/android-gpuimage |
| GPUImage | 滤镜Shader参考 | https://github.com/BradLarson/GPUImage |
| GPUPixel | 滤镜算法参考 | https://gpupixel.pixpark.net/ |
| ML Kit Face Detection | 人脸检测/追踪对焦 | https://developers.google.com/ml-kit/vision/face-detection |
| ML Kit Selfie Segmentation | 人像分割/背景虚化 | https://developers.google.com/ml-kit/vision/selfie-segmentation |
| ML Kit Document Scanner | 高级文档扫描/PDF导出 | https://developers.google.com/ml-kit/vision/doc-scanner |
| RenderScript Toolkit | 高斯模糊（替代RenderScript） | https://github.com/nicktming/renderscript-intrinsics-replacement-toolkit |

## License

```
MIT License

Copyright (c) 2024-2026 qihao

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

---

⭐ **如果这个项目对你有帮助，请点击Star支持！**

---

## ⭐ Star 趋势

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request
