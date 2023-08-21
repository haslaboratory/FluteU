# CP0相关信息

[cp0寄存器编号](https://en.wikichip.org/wiki/mips/coprocessor_0)

# CP0各种寄存器的作用

| 寄存器编号 | 寄存器名     | 读写  | 作用                         |
|-------|----------|-----|----------------------------|
| 0     | Index    | r/w | 用于给tlbp, tlbr, tlbwi指令提供索引 |
| 1     | Random   | r   | 随机的tlb 索引                  |
| 2     | EntryLo0 | r/w | TLB偶数页的入口                  |
| 3     | EntryLo1 | r/w | TLB奇数页的入口                  |
| 4     | Context  | r/w | 指向一个pageTable的入口           |
| 5     | PageMask | r/w | 用于向TLB进行读写，指定页的大小          |
| 6     | Wired    | r/w | 用于分割TLB的wire和random部分      |
| 7     |          |     |                            |
| 8     | BadVaddr | r   | 最近一次地址异常的地址                |
| 9     | Count    | r/w | 进行时钟计数                     |
| 10    | EntryHi  | r/w | TLB读写的操作相关                 |
| 11    | Compare  | r/w | 用于实现时钟中断和计数器               |
| 12    | Status   | r/w | 指示当前的模式，中断以及cpu相关信息        |
| 13    |          |     |                            |
| 14    | EPC      | r/w | cpu从中断恢复的地址                |
| 15    | PRid     | r   | 指示cpu的型号信息                 |
| 16    | Config0  | r   | cpu指令集，架构，TLB等信息           |
| 16    | Config1  | r   | cpu cache配置信息              |
| 17    |          |     |                            |
| 18    |          |     |                            |
| 19    |          |     |                            |
| 20    |          |     |                            |
| 21    |          |     |                            |
| 22    |          |     |                            |
| 23    |          |     |                            |
| 24    |          |     |                            |
| 25    |          |     |                            |
| 26    |          |     |                            |
| 27    |          |     |                            |
| 28    |          |     |                            |
| 29    |          |     |                            |
| 30    |          |     |                            |
| 31    |          |     |                            |

- Index: Index 寄存器