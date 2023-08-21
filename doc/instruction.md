# 支持指令
## 逻辑运算指令 (8)
- AND
- OR
- XOR
- NOR
- ANDI
- ORI
- XORI
- LUI

## 算术运算指令 (21)
- ADD
- ADDI
- ADDU
- ADDIU
- SUB
- SUBU
- SLT
- SLTI
- SLTU
- SLTIU
- MUL
- MULT
- MULTU
- DIV
- DIVU
- MADD: 将HI|LO寄存器组合成一个64位的数字，然后加上rs和rt相乘的结果，存储到HI LO寄存器中
- MADDU: 无符号的MADD
- MSUB: 将HI|LO寄存器合成一个64位的数字，然后减去rs和rt相乘的结果，存储到HI LO寄存器中
- MSUBU：无符号的MSUB
- CLZ: CLZ rt, rs 统计rs寄存器开头的0的数目存到rt寄存器中
- CLO：CLO rt, rs 统计rs寄存器开头的1的数目存到rt寄存器中

## 分支跳转指令 (12)
- BEQ
- BGEZ
- BGEZAL
- BGTZ
- BLEZ
- BLTZ
- BLTZAL
- BNE
- J
- JAL
- JALR
- JR
- BEQL(unimplemented): 和BEQ不同的是只有当分支确定跳转的时候才执行延迟槽
- BGEZALL(unimplemented)
- BGZEL(unimplemented)
- BGTZL(unimplemented)
- BLEZL(unimplemented)
- BLTZALL(unimplemented)
- BTLZL(unimplemented)
- BNEL(unimplemented)

## 访存相关指令 (8)
- LB
- LBU
- LH
- LHU
- LW
- SW
- SH
- SB
- LWL：将从mem[rt + offset]的数据存储到rt寄存器高位
- LWR：将从mem[rt + offset]的数据存储到rt寄存器低位
- SWL: 将rt寄存器的高位存储到mem[rt + offset]的高位
- SWR: 将rt寄存器的高位存储到mem[rt + offset]的低位
- LL(unimplemented): 从内存中读取一个字，来实现下面的RMW操作
- SC(unimplemented): 将一个字存储到内存中

## 移动指令 (6)
- MFC0
- MTC0
- MFHI
- MFLO
- MTHI
- MTLO

## 位移指令 (6)
- SLL
- SLLV
- SRA
- SRAV
- SRL
- SRLV

## 系统调用指令(3)
- SYSCLL
- BREAK
- ERET
- WAIT(unimplemented)
- CACHE(unimplemented)

## TLB
- TLBP: 在TLB中查找和EntryHi寄存器相同的TLB条目对应索引位置到index寄存器，如果不存在，那么在index中写入0x8000
- TLBR：将index寄存器所指示的TLB项目内容写入PageMask，EntryHi，EntryLo0和EntryLo1
- TLBWI：根据index寄存器的值选择一个TLB槽并使用EntryHi和EntryLo寄存器中的数据进行替换
- TLBWR：随机选择一个TLB槽并使用EntryHi和EntryLo寄存器中的数据进行替换

## 条件执行语句
- MOVN rd, rs, rt(unimplemented)当rt寄存器不是0，那么将rs保存到rd寄存器
- MOVZ rd, rs, rt(unimplemented) 当rt寄存器为0的时候，将rs保存到rd寄存器

## 自陷指令
- TGE
- TEGU
- TLT
- TLTU
- TEQ
- TNE
- TGEI
- TGEIU
- TLTI
- TLTIU
- TEQI
- TNEI