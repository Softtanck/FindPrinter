package com.softtanck.findprinter;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
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
        PrinterSearchHelper printerSearchUtils = PrinterSearchHelper.getInstance(MainActivity.this, 15);
        printerSearchUtils.setScanListener(this);
        printerSearchUtils.startScan();
    }

    @Override
    public <T extends BaseDevice> void scanOver(List<T> t) {
    }
}
