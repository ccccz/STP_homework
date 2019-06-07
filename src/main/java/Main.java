import lombok.Data;

/**
 * @author DW
 * @date 2019/5/5
 * 不需要修改，暂时可以使用
 */
@Data
public class Main {

    public static void main(String[] args) throws InterruptedException {
        (new Thread(()->{
           Receiver.main(new String[]{"receive.txt", "127.0.0.1", "6666", "1000", "500", "5000"});
    })).start();
        Thread.sleep(100);
        (new Thread(()->{
            Sender.main(new String[]{"file.txt", "127.0.0.1", "6666", "0.2", "1077", "1000", "0.8", "5678", "500", "5000", "5"});
    })).start();
    }


}


