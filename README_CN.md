# CupTheCnt Java

**CupTheCnt 的 Java 实现。**

本项目是 [toiry921/CupTheCnt](https://github.com/toiry921/CupTheCnt) 的 Java 重写版本，用于从 Nintendo 3DS 开发机 System Updater 使用的 `Contents.cnt` 与 `CupList` 中提取 CIA 文件。

原版使用 C 编写；本项目改用 **Java 17+ + JDK 标准库**实现，目标是提供一个无需额外第三方运行时依赖、可以长期保存和重新运行的单 JAR 工具。

---

## 1. 运行要求

### Java

需要：

* **Java 17 或更高版本**
* 不需要 Python
* 不需要额外 Java 库
* 不需要 Maven
* 不需要 Gradle

确认 Java 是否安装：

```bash
java -version
```

如果能够看到类似：

```text
openjdk version "17..."
```

或者更高版本，即可运行。

---

## 2. 下载与运行

程序最终可以作为一个独立的：

```text
CupTheCnt.jar
```

运行。

最基本的用法：

```bash
java -jar CupTheCnt.jar CupList Contents.cnt
```

默认会在当前目录创建：

```text
updates/
```

并将提取出的 CIA 放入其中。

例如：

```text
.
├── CupList
├── Contents.cnt
├── CupTheCnt.jar
└── updates/
    ├── 0004001000021900.cia
    ├── 0004001000022900.cia
    └── ...
```

---

# 3. 功能

相比原版 CupTheCnt，本版本提供以下功能：

* CIA 提取
* `Contents.cnt` 完整性检查
* 列出 Contents 条目
* 自定义输出目录
* 批量处理多个 `CupList` / `Contents.cnt`
* 递归搜索子目录
* 防止意外覆盖已有 CIA
* 可选强制覆盖
* 对 offset、文件大小和文件边界进行检查
* 使用流式文件复制，不需要将整个 CIA 一次性加载到内存

---

# 4. 基本提取

运行：

```bash
java -jar CupTheCnt.jar CupList Contents.cnt
```

程序会：

1. 读取 `CupList`
2. 取得其中的 Title ID
3. 读取 `Contents.cnt`
4. 验证 `CONT` magic
5. 读取对应的 Contents Entry
6. 根据 offset / offset_end 定位 CIA
7. 将 CIA 提取到 `updates/`

输出文件名使用 Title ID：

```text
000400xxxxxxxxxx.cia
```

---

# 5. 指定输出目录

使用：

```bash
java -jar CupTheCnt.jar --output extracted CupList Contents.cnt
```

结果：

```text
extracted/
├── 000400xxxxxxxxxx.cia
├── 000400xxxxxxxxxx.cia
└── ...
```

输出目录不存在时会自动创建。

---

# 6. 验证 Contents.cnt

如果只想检查文件，而不提取 CIA：

```bash
java -jar CupTheCnt.jar --verify CupList Contents.cnt
```

验证包括：

* `Contents.cnt` 是否足够大
* magic 是否为 `CONT`
* CupList 是否包含有效 Title ID
* CupList 是否存在重复 Title ID
* Contents Entry 是否满足：

  * `offset_end >= offset`
  * CIA 范围没有超过 `Contents.cnt`
* CupList 与 Contents Entry 数量是否匹配

验证成功后不会生成 CIA。

---

# 7. 列出 Contents

使用：

```bash
java -jar CupTheCnt.jar --list CupList Contents.cnt
```

可以查看每个 Title ID 对应的：

* Title ID
* offset
* offset_end
* CIA 大小

例如：

```text
Index  Title ID          Offset       End          Size
-----  ----------------  -----------  -----------  -----------
0      0004001000021900  000000000000  000000001234  0x1234
1      0004001000022900  000000001234  000000005678  0x4444
```

这个模式不会写出 CIA。

---

# 8. 防止覆盖

默认情况下，如果输出目录中已经存在同名 CIA，程序不会覆盖。

例如：

```text
updates/
└── 0004001000021900.cia
```

再次运行时会拒绝覆盖已有文件。

如果确定需要覆盖，可以使用：

```bash
java -jar CupTheCnt.jar --overwrite CupList Contents.cnt
```

---

# 9. 批量处理

可以使用：

```bash
java -jar CupTheCnt.jar --batch input --output output
```

例如：

```text
input/
├── updater1/
│   ├── CupList
│   └── Contents.cnt
├── updater2/
│   ├── CupList
│   └── Contents.cnt
└── updater3/
    ├── CupList
    └── Contents.cnt
```

程序会搜索这些目录并进行批量处理。

输出可以整理到：

```text
output/
├── updater1/
├── updater2/
└── updater3/
```

---

# 10. 递归批处理

如果数据目录有更深的层级，可以使用：

```bash
java -jar CupTheCnt.jar --batch --recursive input --output output
```

例如：

```text
input/
├── Old3DS/
│   ├── 9.0.0/
│   │   ├── CupList
│   │   └── Contents.cnt
│   └── 9.1.0/
│       ├── CupList
│       └── Contents.cnt
└── New3DS/
    └── 9.0.0/
        ├── CupList
        └── Contents.cnt
```

递归模式可以自动搜索这些数据集。

---

# 11. CupList 格式

CupList 的前 `0x800` 字节最多包含：

```text
0x100 个 uint64 Title ID
```

也就是说：

```text
0x800 / 8 = 0x100
```

Java 实现使用：

```java
ByteBuffer
    .order(ByteOrder.LITTLE_ENDIAN)
```

读取。

当读取到：

```text
0x0000000000000000
```

时，认为 Title ID 列表结束。

因此实际的 Title ID 数量可以小于 `0x100`。

---

# 12. Contents.cnt 格式

`Contents.cnt` 的头部由原版 `CupTheCnt` 定义。

固定结构：

```text
Contents_header
├── magic       0x004 bytes
├── unknown     0xBFC bytes
└── entries     0x800 bytes
```

总大小：

```text
0x004 + 0xBFC + 0x800
= 0x1400
```

也就是：

```text
5120 bytes
```

---

## Magic

文件开头必须是：

```text
43 4F 4E 54
```

ASCII：

```text
CONT
```

如果不是：

```text
CONT
```

程序会拒绝处理。

---

# 13. Contents Entry

每一个 Entry 占：

```text
8 bytes
```

结构：

```text
struct Contents_entry {
    uint32_t offset;
    uint32_t offset_end;
};
```

因此：

```text
CIA size = offset_end - offset
```

Java 中使用：

```java
Integer.toUnsignedLong(buffer.getInt())
```

读取，以避免 Java `int` 的有符号问题。

---

# 14. 非常重要：CIA 的实际位置

原版 CupTheCnt 使用：

```c
offset + sizeof(Contents_header) - 2048
```

而：

```text
sizeof(Contents_header) = 0x1400
```

因此：

```text
0x1400 - 0x800
= 0xC00
```

所以 CIA 在 `Contents.cnt` 中的实际位置为：

```text
实际文件位置 = 0xC00 + offset
```

CIA 长度：

```text
长度 = offset_end - offset
```

因此完整公式为：

```text
CIA start = 0xC00 + offset
CIA size  = offset_end - offset
```

这是本 Java 实现中刻意保持与原版 CupTheCnt 一致的关键行为。

**不要将其误写成 `0x1400 + offset`。**

---

# 15. 为什么 Java 版本不一次性读取 CIA？

原版 C 程序的逻辑类似：

```c
contents_buf = malloc(cia_size);

fread(contents_buf, cia_size, 1, contents);

fwrite(contents_buf, cia_size, 1, cia);

free(contents_buf);
```

这意味着：

```text
CIA 多大
↓
就申请多大的 RAM
```

对于大型 CIA 并不理想。

Java 版本改用：

```text
FileChannel.transferTo()
```

直接从：

```text
Contents.cnt
```

复制到：

```text
CIA
```

因此不需要：

```text
CIA size ≈ RAM usage
```

而是：

```text
Contents.cnt
      │
      │ FileChannel
      ▼
   CIA file
```

这对于处理大型 System Updater 数据尤其有用。

---

# 16. 安全检查

Java 版本不会盲目相信 `Contents.cnt` 中的 offset。

在提取之前会检查：

```text
offset_end >= offset
```

并检查：

```text
0xC00 + offset
```

以及：

```text
CIA size
```

是否超出 `Contents.cnt` 的实际文件大小。

因此损坏的 `Contents.cnt` 不应该导致程序直接读取文件末尾之外的数据。

---

# 17. 项目结构

源码项目结构：

```text
CupTheCnt-java/
├── src/
│   └── CupTheCnt.java
├── build/
│   └── CupTheCnt.jar
├── README.md
├── README_CN.md
└── ...
```

最终使用者只需要：

```text
CupTheCnt.jar
```

即可运行。

---

# 18. 从源码编译

如果希望自己重新编译：

```bash
javac -d build src/CupTheCnt.java
```

然后创建 JAR：

```bash
jar --create \
    --file build/CupTheCnt.jar \
    --main-class CupTheCnt \
    -C build .
```

或者使用项目提供的构建脚本。

编译完成后：

```bash
java -jar build/CupTheCnt.jar
```

---

# 19. 为什么选择 Java？

这个项目的目标不是只在今天运行一次，而是作为一个长期保存的 Nintendo 3DS 考古工具。

因此本项目尽量避免：

* Python runtime
* pip
* requirements.txt
* 第三方 Python package
* 第三方 Java runtime dependency
* 复杂的运行环境

理想情况下，运行环境只有：

```text
操作系统
   │
   └── Java 17+
          │
          └── CupTheCnt.jar
```

只要未来仍然存在兼容的 Java 运行环境，就可以直接运行这个 JAR。

---

# 20. 与原版 CupTheCnt 的关系

本项目不是重新设计 `Contents.cnt` 格式。

它的目标是：

> **保持原版 CupTheCnt 的文件格式解释和 CIA 定位方式，同时使用 Java 标准库重新实现。**

尤其保持：

```text
CupList
    ↓
Title ID 顺序

Contents.cnt
    ↓
Contents Entry
    ↓
offset / offset_end
    ↓
0xC00 + offset
    ↓
CIA
```

因此它可以用于与原版 CupTheCnt 相同类型的数据。

---

# 21. 示例工作流

假设有：

```text
SystemUpdater/
├── CupList
└── Contents.cnt
```

首先验证：

```bash
java -jar CupTheCnt.jar \
    --verify \
    SystemUpdater/CupList \
    SystemUpdater/Contents.cnt
```

然后查看：

```bash
java -jar CupTheCnt.jar \
    --list \
    SystemUpdater/CupList \
    SystemUpdater/Contents.cnt
```

确认无误后：

```bash
java -jar CupTheCnt.jar \
    --output extracted \
    SystemUpdater/CupList \
    SystemUpdater/Contents.cnt
```

最终：

```text
extracted/
├── 00040010........cia
├── 00040010........cia
├── 00040030........cia
└── ...
```

---

# 22. 适用场景

本工具主要适用于研究和整理 Nintendo 3DS System Update 数据，例如：

* 开发机 System Updater
* `Contents.cnt`
* `CupList`
* System Update CIA
* 历史固件归档
* 固件版本考古
* Nintendo 3DS 文件格式研究

---

# 23. 注意事项

本工具只负责：

```text
Contents.cnt + CupList
        ↓
CIA 提取
```

它不会：

* 安装 CIA
* 修改 3DS
* 修改 System Updater
* 自动下载 Nintendo CDN 数据
* 自动破解 CIA
* 修改 CIA 内容

因此它本质上是一个**离线文件提取工具**。

---

## 许可证

本 Java 实现采用 MIT 许可证。

### 与原版 CupTheCnt 的关系

本项目是对 [toiry921/CupTheCnt](https://github.com/toiry921/CupTheCnt) 的独立 Java 重写实现。

原版 CupTheCnt 仓库未指定开源许可证。
本项目并不声称原版 C 语言源代码采用 MIT 许可证。

本仓库中的 Java 实现系独立编写，并采用 MIT 许可证。

原项目：

https://github.com/toiry921/CupTheCnt
