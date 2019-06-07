import lombok.Data;

/**
 * @author DW
 * @date 2019/5/5
 * 不需要修改，暂时可以使用
 */
@Data
public class Main {

    public static void main(String[] args) throws InterruptedException {
        (new Thread(() -> {
            //文件Path, 运行ip,运行端口号，maxDelay,mss,msw(msw需为mss的倍数)
            Receiver.main(new String[]{"receive.txt", "127.0.0.1", "6666", "1000", "100", "1000"});
        })).start();
        Thread.sleep(1000);
        (new Thread(() -> {
            //接收方程序的 IP 地址。
            //<port>：接收方程序的端口。
            //<pDrop>：发送方丢包的概率，为一个小于 1 的浮点数。
            //<seedDrop>：发送方丢包的随机数种子，为一个整数。
            //<maxDelay>：发送方发送数据包的最大延时。
            //<pDelay>：发送方发生延迟的延迟率，为一个小于 1 的浮点数。
            //<seedDelay>：发送方发生延迟的随机种子，为一个整数。
            //<MSS>：发送方的最大分段大小。
            //<MWS/MSS>：发送方最大窗口大小，为最大帧大小的倍数，并且必须与receiver方相同
            //<initialTimeout>：初始的超时，为一个整数。
            Sender.main(new String[]{"file.txt", "127.0.0.1", "6666", "0.2", "1077", "1000", "0.2", "5678", "100", "1000", "15"});
        })).start();
    }


}


