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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Created by Tanck on 2015/12/16.
 */
public class PrinterSearchHelper {

    public static final String TAG = "Tanck";

    private static final int TIME_OUT = 5000;//timeout

    private static final int PORT = 9100;//printer

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
        public <T extends BaseDevice> void scanOver(List<T> t);
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


    public void getPrinterOnNetWork() {

        if (isStart)
            return;

        isStart = true;

        if (null == mHandler)
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    Log.d(TAG, "扫描到了:" + ((Printer) msg.obj).ip);
                    list.add((BaseDevice) msg.obj);
                }
            };

        Printer printer = getInfo();

        for (int i = 1; i <= 254; i++) {
            // 添加一个任务
            String starIp = getStarOrEndIp(printer.ip, i, true);
            addTask(starIp, "---");
        }

        isStart = false;

        if (null != listener) {
            listener.scanOver(list);
        }

    }

    /**
     * 添加一个任务
     *
     * @param ip  被扫描IP
     * @param mac 被扫描Mac地址
     */
    private synchronized void addTask(final String ip, final String mac) {

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                Printer printer = new Printer();
                if (sendPacket(ip)) { // success
                    printer.ip = ip;
                    printer.mac = mac;
                    if (null != mHandler) {
                        Message msg = Message.obtain();
                        msg.obj = printer;
                        mHandler.sendMessage(msg);
                    }
                }
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
     * 发包.
     *
     * @param ip
     * @return
     */
    private boolean sendPacket(String ip) {
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
            Log.d(TAG, e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

}
