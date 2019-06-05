# 关键流程

## Receiver

1. 在一开始，首先运行Receiver，状态由CLOSED转变为LISTEN，等待Sender的连接
2. Receiver...这里的握手就是和TCP协议描述的一样


## Sender

### Connect

负责与Receiver建立连接，只要连接没有建立，就不停地尝试与Receiver建立连接。

主要步骤：

1. 发送SYN
2. 接收SYN ACK
3. 发送ACK
4. 连接建立

### Accept

负责接收来自Receiver的packet。

#### 快速重传

当Accept接收到3个连续的拥有相同Acknowlegment的packet，就重传Sequence号为该Acknowlegment的数据。

### Transfer

在建立连接后，Transfer开始运行，负责向Receiver传送数据。

当所要传输的文件内容已经全部收到来自Receiver的ACK后，就主动向Receiver发送FIN，请求中断连接。

#### 模拟丢包

在数据传输阶段，将包打包好准备发送之前，调用函数isDrop()来决定本次要发送的数据包是否丢弃。


