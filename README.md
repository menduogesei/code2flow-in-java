# code2flow-in-java   
## 介绍
Code2flow是一款基于静态分析的代码可视化工具，其核心能力在于通过解析抽象语法树（AST）构建函数调用关系图，最初专注于动态语言如Python、JavaScript、Ruby和PHP的代码逻辑映射。本项目在原作基础上进行了语言层面的重要扩展，将生成调用图的能力从动态语言延伸至静态类型语言Java，标志着工具在跨语言分析领域的突破。通过适配Java严格的类型系统和类继承结构，项目实现了对面向对象编程范式的精准解析，例如对接口实现、多态方法调用链的追踪，同时保留了对动态语言特有的灵活特性（如Python的装饰器、Ruby的元编程）的兼容支持。这种双向演进不仅延续了code2flow"通过代码结构反推逻辑脉络"的设计理念，还通过类型注解、编译时信息等静态语言特性提升了调用图的准确性，减少了动态语言中因运行时不确定性导致的歧义边。随着开发者社区的持续贡献，未来计划进一步突破语言类型限制，探索对C++、Go等编译型语言的支持，使工具逐步演变为通用型代码逻辑分析平台，为多语言混合项目提供全景式的调用依赖透视
## 基本算法
1. 把源代码文件翻译成语法树
将源代码转换为语法树时，通过解析器对代码进行词法分析和语法分析，生成抽象语法树（AST）。AST的每个节点对应代码中的具体元素（如函数、变量、条件分支），并记录其语法结构关系。过程中需处理语言特定的语法规则（如Python缩进、Ruby块语法），通过遍历AST识别函数定义的位置及作用域，同时标记上下文。
2. 找到所有的函数定义。
在遍历语法树（AST）时，依据语言特性识别函数定义节点。Python的def关键字。通过解析器提取函数名、参数列表及作用域范围，同时处理嵌套定义。识别过程中需建立函数与所属命名空间（模块、类）的映射关系，并记录其定义位置，以便后续跨文件调用分析。
3. 确定函数在哪里被调用。
在AST中定位函数调用时，遍历所有调用表达式节点，解析调用者名称及参数。对于显式调用，直接提取函数名。针对面向对象语言，需识别类方法调用（如obj.method()）并关联其所属类标准库/外部函数与本地同名函数可能错误链接​（如将searcher.search()误指向本地search()）；跨文件调用需显式指定源文件，动态语言特性（eval/send）依赖启发式规则，无法保证完全精确。
4. 连接节点
基于AST建立显式调用边，处理继承（显式父类声明连接方法）和装饰器（单向关联）。跨文件调用仅识别import/require导入路径，动态方法（如apply）通过变量名静态匹配推测目标。输出基础调用链路（含误报），排除闭包、条件触发等动态调用，不添加占位节点或拓扑优化，确保结果精简可逆。
## 作用
1. 识别没有被调用的函数，这类函数可以被去掉，减少存储空间。
该工具能自动扫描项目代码，找出从未被使用的部分，例如某些独立存在的函数或模块。这些未被调用的代码段往往占据存储空间且增加维护复杂度，通过精准识别并移除它们，可有效精简代码结构，避免资源浪费
2. 让新进入项目的开发者了解项目的调用图，使他们迅速熟悉项目架构，从而快速上手。
对于刚加入项目的开发者，复杂的代码逻辑常令人望而生畏。Code2flow通过生成清晰的图表，直观展示各个功能模块之间的调用路径和层级关系，如同为新人提供一张“代码地图”。这种方式能帮助快速理解项目骨架，减少沟通成本，缩短上手周期
3. 便于用来测试的程序分析，寻找危险函数，这些函数往往会调用危险的系统调用。
在测试和审查阶段，该工具能追踪代码中可能引发问题的环节，例如某些涉及敏感操作的函数调用链。通过可视化分析，团队可快速定位潜在的高风险节点，优先进行针对性审查或加固，从而提升系统稳定性。
## 下载工具和依赖
### graphviz
如果没有下载此画图工具，你能在[这里](https://graphviz.org/download/)找到下载方法。
### Acorn
如果你没有下载JavaScript解析依赖，你能在[这里](https://www.npmjs.com/package/acorn)找到下载方法。
### Parser
如果你没有下载Ruby解析依赖，你能在[这里](https://github.com/whitequark/parser)找到下载方法。
### PHP-Parser
如果你没有下载PHP解析依赖，你能在[这里](https://github.com/nikic/PHP-Parser)找到下载方法。
## 用法
以JavaScript为例，
为了生成一个DOT文件，运行以下命令：
```bash
java -jar code2flow-in-java.jar 1.js
```
为了执行多个文件，运行以下命令：
```bash
java -jar code2flow-in-java.jar 2.js 3.js
```
或者
```bash
java -jar code2flow-in-java.jar directory --language js
```
或者
```bash
java -jar code2flow-in-java.jar *.js
```
为了提取整个调用图的一个子图，运行以下命令：
```bash
java -jar code2flow-in-java.jar 4.js --target-function my_func --upstream-depth=1 --downstream-depth=1
```
如果需要帮助的话，运行以下命令：
```bash
java -jar code2flow-in-java.jar --help
```