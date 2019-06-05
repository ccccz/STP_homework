import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Date;

public  class CRC16 {


    /**
     * 获取验证码byte数组，基于Modbus CRC16的校验算法
     */
    public static byte[] generateCRC(byte[] content) {
        int len = content.length;

        // 预置 1 个 16 位的寄存器为十六进制FFFF, 称此寄存器为 CRC寄存器。
        int crc = 0xFFFF;
        int i, j;
        for (i = 0; i < len; i++) {
            // 把第一个 8 位二进制数据 与 16 位的 CRC寄存器的低 8 位相异或, 把结果放于 CRC寄存器
            crc = ((crc & 0xFF00) | (crc & 0x00FF) ^ (content[i] & 0xFF));
            for (j = 0; j < 8; j++) {
                // 把 CRC 寄存器的内容右移一位( 朝低位)用 0 填补最高位, 并检查右移后的移出位
                if ((crc & 0x0001) > 0) {
                    // 如果移出位为 1, CRC寄存器与多项式A001进行异或
                    crc = crc >> 1;
                    crc = crc ^ 0xA001;
                } else
                    // 如果移出位为 0,再次右移一位
                    crc = crc >> 1;
            }
        }
        return intToBytes(crc);
    }
    /**
     * 将int转换成byte数组，低位在前，高位在后
     * 改变高低位顺序只需调换数组序号
     */
    private static byte[] intToBytes(int value)  {
        byte[] src = new byte[2];
        src[1] =  (byte) ((value>>8) & 0xFF);
        src[0] =  (byte) (value & 0xFF);
        return src;
    }

    public static boolean checkCRC(Message message) {
        byte[] content = message.getContent();
        byte[] currentCRC = message.getCrc16();
        byte[] correctCRC = generateCRC(content);
        return Arrays.equals(currentCRC,correctCRC);
    }

    //TEST
//    public static void main(String args[]) {
//        byte[] crc1 = generateCRC(new byte[]{1, 2, 3, 4});
//        byte[] crc2 = generateCRC(new byte[]{1, 2, 3, 4});
//        Message toSendMessage = new Message();
//        toSendMessage.setACK(false);
//        toSendMessage.setSYN(false);
//        toSendMessage.setFIN(false);
//        toSendMessage.setRST(false);
//        toSendMessage.setSequence(5);
//        toSendMessage.setContent(new byte[]{1, 2, 3, 4});
//        byte[] toSendCRC = CRC16.generateCRC(new byte[]{1, 2, 3, 4});
//        toSendMessage.setCrc16(toSendCRC);
//        toSendMessage.setTime((new Date()).getTime());
//        System.out.println(toSendMessage.toString());
//        System.out.println(toSendMessage.deMessage(toSendMessage.enMessage()).toString());
//    }
}
