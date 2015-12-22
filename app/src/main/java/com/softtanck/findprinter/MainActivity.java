package com.softtanck.findprinter;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import com.softtanck.findprinter.bean.BaseDevice;
import com.softtanck.findprinter.utils.PrinterSearchHelper;

import java.util.List;

public class MainActivity extends AppCompatActivity implements PrinterSearchHelper.ScanListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }


    public void scan(View view) {
        PrinterSearchHelper printerSearchUtils = PrinterSearchHelper.getInstance(MainActivity.this, 50);
        printerSearchUtils.setScanListener(this);
        printerSearchUtils.startScan();
    }

    // 注意这儿扫描完毕,集合可能为空,需要自己判断
    @Override
    public <T extends BaseDevice> void scanOver(List<T> t) {
        Log.d("Tanck", "扫描完毕");
        if (0 < t.size()) {
            Log.d("Tanck", t.get(0).ip + "--" + t.get(0).mac + "--size:" + t.size());
        }
    }

    @Override
    public void currentPosition(int progress) {
        Log.d("Tanck", "当前进度-----" + progress);
    }
}
