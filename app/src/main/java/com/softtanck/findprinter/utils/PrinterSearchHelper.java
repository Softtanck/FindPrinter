package com.softtanck.findprinter.utils;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.softtanck.findprinter.bean.BaseDevice;
import com.softtanck.findprinter.bean.Printer;

import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Tanck on 2015/12/16.
 */
public class PrinterSearchHelper {

    /**
     * 获取网络设备名字的OID
     */
    public final static String W1110_NETWORK = ".1.3.6.1.4.1.10917.1.5.1.1.17.1.0";

    /**
     * 获取共享设备名字的OID
     */
    public final static String BROTHRE_QL_1050_LE = ".1.3.6.1.4.1.77.1.2.29.1.1.18.66.114.111.116.104.101.114.32.81.76.45.49.48.53.48.32.76.69";

    public final static String NO_OBJECT = "noSuchObject";

    public final static String TAG = "Tanck";

    /**
     * 获取设备指定的OID
     */
    private String[] mSnmpShareOids = {W1110_NETWORK, BROTHRE_QL_1050_LE};

    private int START_IP = 1;//开始IP

    private int END_IP = 254;// 结束IP

    private final static int TIME_OUT = 3000;//timeout

    private final static int PORT = 9100;//printer

    private final static int SNMP_PORT = 161; //snmp协议端口

    private final static int snmpVersion = SnmpConstants.version2c;

    private final static String community = "public";

    private static int mTimes = 0; // 搜索次数.

    private final static int SUCCESS_SCAN = 0x1;// 搜索成功

    private final static int FAIL_SCAN = 0x2; // 搜索失败

    private List<String> macs = new ArrayList<>();//Mac被检测的Mac集合

    private final static String DEFAULT_MAC = "00:00:00:00:00:00";

    private final static String MAC_RE = "^%s\\s+0x1\\s+0x2\\s+([:0-9a-fA-F]+)\\s+\\*\\s+\\w+$";

    private final static int BUFF = 8 * 1024;

    public static PrinterSearchHelper instance;

    /**
     * 判断标志
     */
    private boolean isStart;

    /**
     * 存放集合
     */
    private List<BaseDevice> list = new ArrayList<>();

    /**
     * 上下文
     */
    private Context context;

    /**
     * 工作的线程
     */
    private Thread mThread;

    /**
     * 线程中的消息处理
     */
    private Handler mPoolThreadHandler;
    /**
     * 线程池
     */
    private ExecutorService mThreadPool;

    /**
     * 任务队列
     */
    private LinkedList<Runnable> mTask;

    /**
     * 引入一个值为1的信号量，防止mPoolThreadHander未初始化完成
     */
    private Semaphore mSemapHore = new Semaphore(0);

    /**
     * 引入一个值为threadCount的信号量，由于线程池内部也有一个阻塞线程，防止加入任务的速度过快，使LIFO效果不明显
     */
    private Semaphore mPoolSemaphore;

    /**
     * 包数据
     */
    private DatagramPacket packet;

    /**
     * callBack
     */
    private Handler mHandler;

    /**
     * 监听
     */
    private ScanListener listener;

    /**
     * 扫描监听
     */
    public interface ScanListener {
        /**
         * 扫描完毕
         *
         * @param t
         * @param <T>
         */
        public <T extends BaseDevice> void scanOver(List<T> t);

        /**
         * 当前位置
         *
         * @param progress
         */
        public void currentPosition(int progress);
    }

    /**
     * 设置监听
     *
     * @param listener
     */
    public void setScanListener(ScanListener listener) {
        this.listener = listener;
    }

    /**
     * @param context
     * @param threadCount
     * @return
     */
    public static PrinterSearchHelper getInstance(Context context, int threadCount) {
        if (null == instance) {
            synchronized (PrinterSearchHelper.class) {
                if (null == instance) {
                    instance = new PrinterSearchHelper(context, threadCount);
                }
            }
        }
        return instance;
    }

    private PrinterSearchHelper(Context context, int threadCount) {
        this.context = context;
        init(threadCount);
    }


    /**
     * 获取开始或者结束Ip
     *
     * @param ip      源地址
     * @param isStart true:获取开始.  false:获取结束
     * @return
     */
    private String getStarOrEndIp(String ip, int count, boolean isStart) {
        ip = ip.substring(0, (ip.lastIndexOf(".") + 1));
        StringBuffer buffer = new StringBuffer(ip);
        ip = isStart ? buffer.append(count).toString() : buffer.append(count).toString();
        return ip;
    }


    private void init(int threadCount) {
        //工作线程
        mThread = new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mPoolThreadHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        mThreadPool.execute(getTask());
                        try {
                            mPoolSemaphore.acquire();//信号量 + 1
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                };
                mSemapHore.release();//初始化完成后信号量 -1
                Looper.loop();
            }
        };


        //初始化线程池
        mThreadPool = Executors.newScheduledThreadPool(threadCount);
        //初始化信号量
        mPoolSemaphore = new Semaphore(threadCount);
        //初始化线程列表
        mTask = new LinkedList<>();
        mThread.start(); // 开始处理
    }


    public void startScan() {


        if (isStart)
            return;
        list.clear();
        mTimes = 0;
        isStart = true;
//        macs.add("10:e6:ae");//添加

        if (null == mHandler)
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {

                    int code = msg.what;
                    ++mTimes;
                    switch (code) {
                        case SUCCESS_SCAN:
                            Log.d(TAG, "扫描到了:" + ((Printer) msg.obj).ip);
                            synchronized (PrinterSearchHelper.class) {
                                list.add((BaseDevice) msg.obj);
                            }
                            break;
                        case FAIL_SCAN:
                            break;
                    }
                    if (null != listener) {
                        listener.currentPosition((int) ((1.00f * mTimes) / END_IP * 100));
                    }
                    // 保证所有线程都执行完毕后.
                    if (END_IP - START_IP + 1 <= mTimes) {
                        if (null != listener) {
                            macs.clear();
                            listener.scanOver(list);
                        }
                    }
                }
            };

        Printer printer = getInfo();

        for (int i = START_IP; i <= END_IP; i++) {
            // 添加一个任务
            String starIp = getStarOrEndIp(printer.ip, i, true);
            addTask(starIp);
        }

        isStart = false;

    }

    /**
     * 添加一个任务
     *
     * @param ip 被扫描IP
     */

    private synchronized void addTask(final String ip) {

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                Printer printer = null;
                String mac = getHardwareAddress(ip);

//                if (sendPacketBySocketForScan(ip)) { // success
//                    if (0 < macs.size()) { // 证明需要过滤
//                        if (isContain(mac)) {
//                            printer = new Printer(ip, mac);
//                        }
//                    } else {
//                        printer = new Printer(ip, mac);
//                    }
//                }
                String tempName;
                for (int i = 0; i < mSnmpShareOids.length; i++) {
                    tempName = sendSnmpOIDForScan(ip, mSnmpShareOids[i]);
                    if (null != tempName) {//证明网络中有共享的
                        Log.d(TAG, "通过SNMP协议找到了:" + tempName);
                        printer = new Printer(ip, mac);
                        printer.setName(tempName);
                    } else if (null == printer) { //证明Snmp中没有此打印机,扫描其端口
                        if (sendPacketBySocketForScan(ip)) { // success
                            if (0 < macs.size()) { // 证明需要过滤
                                if (isContain(mac)) {
                                    printer = new Printer(ip, mac);
                                }
                            } else {
                                printer = new Printer(ip, mac);
                            }
                        }
                    }
                }
                sendMsg(printer);
            }
        };

        if (null == mPoolThreadHandler) {
            try {
                mSemapHore.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mTask.add(runnable);
        mPoolThreadHandler.sendEmptyMessage(0x1000);
        mPoolSemaphore.release();//信号量 -1
    }

    private void sendMsg(Printer printer) {
        if (null != mHandler)
            if (null == printer) { // 失败的
                mHandler.sendEmptyMessage(FAIL_SCAN);
            } else {
                Message msg = Message.obtain();
                msg.obj = printer;
                msg.what = SUCCESS_SCAN;
                mHandler.sendMessage(msg);
            }
    }

    /**
     * 获取任务
     *
     * @return
     */
    private synchronized Runnable getTask() {
        if (0 < mTask.size()) {
            return mTask.removeLast();
        }
        return null;
    }

    /**
     * 获取一个printer信息
     *
     * @return
     */

    private Printer getInfo() {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi.getConnectionInfo();
        String macText = info.getMacAddress();
        String ipText = intToIp(info.getIpAddress());
        Printer printer = new Printer();
        printer.ip = ipText;
        printer.mac = macText;
        return printer;
    }

    /**
     * 将整型转为IP
     *
     * @param ip
     * @return
     */
    private String intToIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "."
                + ((ip >> 24) & 0xFF);
    }


    /**
     * 根据IP获取其mac地址
     *
     * @param ip
     * @return
     */
    private synchronized String getHardwareAddress(String ip) {
        String hw = DEFAULT_MAC;
        try {
            if (ip != null) {
                String ptrn = String.format(MAC_RE, ip.replace(".", "\\."));
                Pattern pattern = Pattern.compile(ptrn);
                BufferedReader bufferedReader = new BufferedReader(new FileReader("/proc/net/arp"), BUFF);
                String line;
                Matcher matcher;
                while ((line = bufferedReader.readLine()) != null) {
                    matcher = pattern.matcher(line);
                    if (matcher.matches()) {
                        hw = matcher.group(1);
                        break;
                    }
                }
                bufferedReader.close();
            } else {
                Log.e(TAG, "ip is null");
            }
        } catch (IOException e) {
            Log.e(TAG, "Can't open/read file ARP: " + e.getMessage());
            return hw;
        }
        return hw;
    }


    /**
     * 判断是否为指定的Mac
     *
     * @param mac
     * @return
     */
    private boolean isContain(String mac) {
        for (String s : macs) {
            Log.d(TAG, "isContain: " + s);
            if (mac.startsWith(s))
                return true;
        }
        return false;
    }

    /**
     * 发包.
     *
     * @param ip
     * @return
     */
    private boolean sendPacketBySocketForScan(String ip) {
        Log.d(TAG, "开始扫描:" + ip);
        try {
            InetAddress serverAddress = InetAddress.getByName(ip);//
            String str = "AT+FIND=?\r\n";//pinter special string.
            byte data[] = str.getBytes();//
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddress, PORT);// test
            SocketAddress sa = new InetSocketAddress(ip, PORT);
            Socket client = new Socket();
            client.connect(sa, TIME_OUT);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }


    /**
     * 通过snmp协议发包
     *
     * @param ip
     * @param oid
     * @return
     */
    private String sendSnmpOIDForScan(String ip, String oid) {
        try {
            String devicesName;
            TransportMapping transport = new DefaultUdpTransportMapping();
            transport.listen();

            // Create Target Address object
            CommunityTarget comtarget = new CommunityTarget();
            comtarget.setCommunity(new OctetString(community));
            comtarget.setVersion(snmpVersion);
            comtarget.setAddress(new UdpAddress(ip + "/" + SNMP_PORT));
            comtarget.setRetries(1);//retry次数
            comtarget.setTimeout(TIME_OUT);

            // Create the PDU object
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(oid)));
            pdu.setType(PDU.GET);
            pdu.setRequestID(new Integer32(1));

            // Create Snmp object for sending data to Agent
            Snmp snmp = new Snmp(transport);

//            Log.d(TAG, "sendSnmpOIDForScan: Sending Request to Agent...");
            ResponseEvent response = snmp.get(pdu, comtarget);

            // Process Agent Response
            if (response != null) {
//                Log.d(TAG, "sendSnmpOIDForScan: Got Response from Agent ");
                PDU responsePDU = response.getResponse();

                if (responsePDU != null) {
//                    int errorStatus = responsePDU.getErrorStatus();
//                    int errorIndex = responsePDU.getErrorIndex();
//                    String errorStatusText = responsePDU.getErrorStatusText();

                    if (responsePDU.getErrorStatus() == PDU.noError) {
                        Vector vector = responsePDU.getVariableBindings();
                        devicesName = vector.get(0).toString();
                        devicesName = devicesName.replace(oid.substring(1) + " = ", "");
                        Log.d(TAG, "sendSnmpOIDForScan: " + devicesName);
                        return devicesName.equals(NO_OBJECT) ? null : devicesName;
                    }
                }
            }
            snmp.close();
        } catch (Exception e) {
            Log.d(TAG, "sendSnmpOIDForScan: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 添加一个过滤Ip
     *
     * @param mac
     */
    public void addMac(String mac) {
        if (!macs.contains(mac))
            macs.add(mac);
    }

    /**
     * 添加需要过滤Ip集合
     *
     * @param remoteMacs
     */
    public void addMacs(List<String> remoteMacs) {
        if (null == remoteMacs)
            return;
        for (String mac : remoteMacs) {
            addMac(mac);
        }
    }
}
