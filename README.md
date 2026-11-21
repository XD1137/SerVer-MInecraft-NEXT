# NekoProxy

**NekoProxy** 是一个使用 Rust 编写、以 [ViaProxy](https://github.com/ViaVersion/ViaProxy) 为协议转换核心的 Minecraft Java Edition TCP 代理。项目将轻量级连接转发、连接状态跟踪与流量统计交给 Rust 处理，并通过 ViaProxy / ViaVersion 的协议管线兼容不同 Minecraft 客户端版本；同时提供聊天消息注入插件，支持从代理控制台向在线玩家发送系统消息。

> 项目仍在持续开发中。Minecraft 协议兼容性取决于所使用的 ViaProxy 版本及其协议支持范围。

## 功能

- **Rust TCP 代理入口**：默认监听 `0.0.0.0:25565`，接收 Minecraft Java Edition 客户端连接。
- **ViaProxy 协议转换**：由独立 Java 子进程处理实际的 Minecraft 协议转换与后端连接。
- **自定义后端**：可指定目标服务器地址和端口；默认目标为项目配置的时光墟服务器。
- **聊天消息注入**：自动构建并安装 ViaProxy 插件，通过 ViaVersion 转换管线发送系统聊天消息。
- **连接状态跟踪**：跟踪连接数、玩家状态以及握手、登录和配置阶段。
- **流量统计**：统计上行、下行和总流量，并定期输出报告。
- **可配置 MOTD 与 LAN 发现**：支持自定义局域网广播 MOTD、指定广播网卡或关闭广播。
- **可配置日志**：支持调整日志等级，并将日志写入 `Log/proxy.log`。

## 工作方式

```text
Minecraft 客户端
       |
       | TCP（默认 :25565）
       v
   NekoProxy（Rust）
       |
       | 本机 TCP（默认 127.0.0.1:25568）
       v
 ViaProxy（Java / ViaVersion）
       |
       | Minecraft 协议转换与转发
       v
   目标 Minecraft 服务器

NekoProxy Rust  <---- 本机控制通道 :25626 ----> ViaProxy 聊天插件
```

Rust 代理负责接入客户端、跟踪连接状态和统计流量；ViaProxy 负责协议转换及后端连接。聊天插件使用独立的本机控制通道接收消息请求。

## 环境要求

- **Rust 工具链**：安装稳定版 Rust（包含 Cargo）。
- **Java**：安装可用的 Java 运行环境，并确保 `java` 命令可以从终端执行。
- **ViaProxy**：将 `ViaProxy.jar` 或 `ViaProxy-3.4.14.jar` 放在项目根目录。
- **Gradle 构建环境**：首次启动时，项目会调用 `scripts/build-viaproxy-plugin.ps1`（Windows）或 `scripts/build-viaproxy-plugin.sh`（Linux / macOS）自动构建聊天插件。请按对应脚本的要求准备 Gradle 环境。

## 快速开始

### 1. 获取项目

```powershell
git clone https://github.com/MITS-CN/SerVer-MInecraft-NEXT.git
cd SerVer-MInecraft-NEXT
```

### 2. 放置 ViaProxy

下载 ViaProxy，并将 JAR 放在项目根目录，文件名使用以下任意一种：

- `ViaProxy.jar`
- `ViaProxy-3.4.14.jar`

### 3. 启动

```powershell
cargo run --release
```

首次启动时，程序会尝试自动构建聊天插件、将插件复制到 `plugins/`，然后启动 ViaProxy。构建产物和运行日志会生成在项目目录中。

如果希望直接运行编译后的程序：

```powershell
cargo build --release
.\target\release\rust-proxy.exe
```

Linux / macOS 可运行：

```sh
cargo build --release
./target/release/rust-proxy
```

## 命令行参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--listen` | `0.0.0.0:25565` | NekoProxy 对 Minecraft 客户端开放的监听地址 |
| `--target` | `shiguangxu.minecraft.momeow.top:20226` | 后端 Minecraft 服务器地址 |
| `--motd` | 内置时光墟 MOTD | 局域网发现广播使用的 MOTD |
| `--lan-ip` | 未指定 | 指定用于 UDP 局域网发现的本机 IP |
| `--no-broadcast` | 关闭 | 禁用 UDP 局域网发现广播 |
| `--log-level` | `INFO` | 日志等级 |
| `--viaproxy-port` | `25568` | Rust 与 ViaProxy 之间的本机监听端口 |

也可以将后端地址作为位置参数传入：

```sh
cargo run --release -- play.example.com:25565
```

示例：更改客户端监听端口、后端地址和 MOTD：

```sh
cargo run --release -- --listen 0.0.0.0:25566 --target play.example.com:25565 --motd "My Minecraft Server"
```

示例：指定 LAN 广播网卡并调整日志等级：

```sh
cargo run --release -- --lan-ip 192.168.1.10 --log-level DEBUG
```

关闭 LAN 发现：

```sh
cargo run --release -- --no-broadcast
```

## 控制台命令

程序提供标准输入控制台，支持以下命令：

| 命令 | 说明 |
| --- | --- |
| `list` | 查看当前跟踪的玩家连接数 |
| `say <消息>` | 通过 ViaProxy 聊天插件向在线玩家发送系统消息 |
| `quit` / `exit` | 请求退出代理 |

例如：

```text
say 欢迎来到时光墟！
list
quit
```

## 网络与安全说明

- 默认的 Minecraft 监听地址是 `0.0.0.0:25565`，这意味着代理会接受来自所有网络接口的连接。请通过防火墙控制可访问范围。
- ViaProxy 的监听地址由 Rust 程序设置为本机回环地址，默认端口为 `25568`。
- 聊天插件控制通道使用本机地址 `127.0.0.1:25626`，不应将其暴露到公网。
- **重要：当前代码中的 TCP 管理控制台绑定在 `0.0.0.0:25625`，且没有身份验证。不要将该端口暴露给不可信网络。** 在部署到公网或共享网络前，应先将其限制为回环地址或补充可靠的身份验证。
- ViaProxy 启动参数当前使用 `--auth-method NONE`。请确认后端服务器的在线模式、身份验证和访问控制符合你的部署需求。

## 项目结构

```text
.
├── src/                    # Rust 代理、协议处理、控制台、日志与统计
│   ├── main.rs
│   ├── proxy.rs
│   ├── viaproxy.rs
│   ├── console.rs
│   ├── lan.rs
│   ├── mc.rs
│   ├── traffic.rs
│   ├── logger.rs
│   └── util.rs
├── viaproxy-plugin/        # ViaProxy 聊天注入插件工程
├── scripts/                # 插件自动构建脚本
├── data/                   # 日志文本与界面字符等运行数据
├── plugins/                # 运行时安装的 ViaProxy 插件
├── Log/                    # 运行日志
├── Cargo.toml
└── ViaProxy.jar            # 需自行放置的 ViaProxy 程序
```

## 开发

格式检查：

```sh
cargo fmt --check
```

构建：

```sh
cargo build --release
```

运行：

```sh
cargo run --release -- --log-level DEBUG
```

## 许可证

NekoProxy 项目中由贡献者编写的原创代码采用 **MIT License**。这包括项目自身的 Rust 代码及自行编写的 ViaProxy 插件代码。

ViaProxy、ViaVersion 和其他第三方依赖不因本声明而改变其原有许可证，仍分别遵循各自的许可条款。Minecraft 及相关商标和知识产权归其各自权利人所有；本项目与 Mojang 或 Microsoft 无隶属关系。
