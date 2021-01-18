package com.tistory.sonar2.btcar;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;


public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final int REQUEST_ENABLE_BT = 10;
    private final byte mDelimiter = '\n';
    Set<BluetoothDevice> mDevices;
    BluetoothSocket mSocket = null;
    int readBufferPosition;
    TextView mTvDisplay;
    private BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    private OutputStream mOutputStream = null;
    private InputStream mInputStream = null;
    private byte[] readBuffer;
    private Thread mWorkerThread = null;
    private boolean isPlay = false, isConn = false;
    private SensorManager sensorManager = null;
    private Sensor sensor = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        assert sensorManager != null;
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        if (mBluetoothAdapter == null) {
            Toast.makeText(getApplicationContext(), "장치가 불루투스를 지원하지 않음", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(getApplicationContext(), "장치가 불루투스를 지원함", Toast.LENGTH_SHORT).show();
            if (!mBluetoothAdapter.isEnabled()) { //블루투스 지원하지만 비활성상태인 경우
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } else {
                //블루투스를 지원하며 활성상태인경우 페어링된 기기목록을 보여주고 연결할 장치를 선택
                selectDevice();
            }
        }
        mTvDisplay = findViewById(R.id.tvDisplay);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(getApplicationContext(), "블루투스가 활성화", Toast.LENGTH_LONG).show();
            } else if (resultCode == RESULT_CANCELED) {
                // 블루투스가 비활성 상태임
                //finish();  //  어플리케이션 종료
                Toast.makeText(getApplicationContext(), "블루투스 비활성", Toast.LENGTH_LONG).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    void selectDevice() {
        mDevices = mBluetoothAdapter.getBondedDevices();
        final int mPairedDeviceCount = mDevices.size();

        if (mPairedDeviceCount == 0) {
            //  페어링 된 장치가 없는 경우
            Toast.makeText(getApplicationContext(), "페러링된 불루투스를 장치 없음", Toast.LENGTH_LONG).show();
            //finish();    // 어플리케이션 종료
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("블루투스 장치 선택");


        // 페어링 된 블루투스 장치의 이름 목록 작성
        List<String> listItems = new ArrayList<>();
        for (BluetoothDevice device : mDevices) {
            listItems.add(device.getName());
        }
        listItems.add("취소");    // 취소 항목 추가

        final CharSequence[] items = listItems.toArray(new CharSequence[0]);

        builder.setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int item) {
                if (item >= mPairedDeviceCount) {
                    // 연결할 장치를 선택하지 않고 '취소'를 누른 경우
                    //finish();
                    Toast.makeText(getApplicationContext(), "연결장치 선택 취소", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getApplicationContext(), String.valueOf(item), Toast.LENGTH_SHORT).show();
                    // 연결할 장치를 선택한 경우
                    // 선택한 장치와 연결을 시도함
                    connectToSelectedDevice(items[item].toString());
                }
            }
        });


        builder.setCancelable(false);    // 뒤로 가기 버튼 사용 금지
        AlertDialog alert = builder.create();
        alert.show();
    }


    void connectToSelectedDevice(String selectedDeviceName) {
        BluetoothDevice mRemoteDevice = getDeviceFromBondedList(selectedDeviceName);
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

        try {
            // 소켓 생성
            mSocket = mRemoteDevice.createRfcommSocketToServiceRecord(uuid);
            // RFCOMM 채널을 통한 연결
            mSocket.connect();

            // 데이터 송수신을 위한 스트림 열기
            mOutputStream = mSocket.getOutputStream();
            mInputStream = mSocket.getInputStream();

            // 데이터 수신 준비
            beginListenForData();
            isConn = true;
        } catch (Exception e) {
            // 블루투스 연결 중 오류 발생
            //finish();   // 어플 종료
            Toast.makeText(getApplicationContext(), "블루투스 연결 오류", Toast.LENGTH_LONG).show();
            isConn = false;
        }
    }

    BluetoothDevice getDeviceFromBondedList(String name) {
        BluetoothDevice selectedDevice = null;

        for (BluetoothDevice device : mDevices) {
            if (name.equals(device.getName())) {
                selectedDevice = device;
                break;
            }
        }
        return selectedDevice;
    }

    void beginListenForData() {
        final Handler handler = new Handler();

        readBuffer = new byte[1024];  //  수신 버퍼
        readBufferPosition = 0;        //   버퍼 내 수신 문자 저장 위치

        // 문자열 수신 쓰레드
        mWorkerThread = new Thread(new Runnable() {
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {

                    try {
                        int bytesAvailable = mInputStream.available();    // 수신 데이터 확인
                        if (bytesAvailable > 0) {                     // 데이터가 수신된 경우
                            byte[] packetBytes = new byte[bytesAvailable];
                            mInputStream.read(packetBytes);
                            for (int i = 0; i < bytesAvailable; i++) {
                                byte b = packetBytes[i];
                                if (b == mDelimiter) {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, StandardCharsets.US_ASCII);
                                    readBufferPosition = 0;

                                    handler.post(new Runnable() {
                                        public void run() {
                                            // 수신된 문자열 데이터에 대한 처리 작업
                                            //mEditReceive.setText(mEditReceive.getText().toString() + data+ mStrDelimiter);
                                            Toast.makeText(getApplicationContext(), data, Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                } else {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    } catch (IOException ex) {
                        // 데이터 수신 중 오류 발생.
                        //finish();
                        Toast.makeText(getApplicationContext(), "수신오류", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        mWorkerThread.start();
    }

    void sendData(char ldir, int lspd, char rdir, int rspd) {
        //msg += mDelimiter;    // 문자열 종료 표시
        //String msg = String.format("%c%d%c%d",ldir,lspd, rdir,rspd);
        //Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        try {
            //mOutputStream.write(msg.getBytes());    // 문자열 전송
            mOutputStream.write(ldir);
            mOutputStream.write(lspd);
            mOutputStream.write(rdir);
            mOutputStream.write(rspd);
        } catch (Exception e) {
            // 문자열 전송 도중 오류가 발생한 경우.
            //finish();    //  APP 종료
            Toast.makeText(getApplicationContext(), "전송오류", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            mWorkerThread.interrupt();   // 데이터 수신 쓰레드 종료
            mInputStream.close();
            mOutputStream.close();
            mSocket.close();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "종료", Toast.LENGTH_SHORT).show();
        }

        super.onDestroy();
    }


    public void PlayGo(View view) {
        isPlay = true;
    }

    public void stop(View view) {
        isPlay = false;
        if(isConn) sendData('+', 0, '+', 0);
        mTvDisplay.setText("멈췄음");
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void onSensorChanged(SensorEvent event) {
        char chLDir, chRDir;
        int iLSpeed, iRSpeed;
        if (event.sensor == sensor) {
            //include gravity
            int gAccX = (int) (event.values[0] * 12.5f);
            int gAccY = (int) (event.values[1] * 25.0f);
            //float gAccZ = event.values[2];

            if (gAccY < 0) {
                iLSpeed = gAccY + gAccX;
                iRSpeed = gAccY - gAccX;
            } else {
                iLSpeed = gAccY - gAccX;
                iRSpeed = gAccY + gAccX;
            }

            if (iLSpeed < 0) {
                chLDir = '+';
                iLSpeed *= -1;
            } else {
                chLDir = '-';
            }
            if (iRSpeed < 0) {
                chRDir = '+';
                iRSpeed *= -1;
            } else {
                chRDir = '-';
            }
            if(iLSpeed<50) iLSpeed=0;
            else iLSpeed+=80;
            if(iLSpeed>255) iLSpeed=255;

            if(iRSpeed<50) iRSpeed=0;
            else iRSpeed+=80;
            if(iRSpeed>255) iRSpeed=255;


            if (isPlay) {
                mTvDisplay.setText(String.format("%c%d , %c%d", chLDir, iLSpeed, chRDir, iRSpeed));
                if(isConn) sendData(chLDir, iLSpeed, chRDir, iRSpeed);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(isConn) sendData('+', 0, '+', 0);
        sensorManager.unregisterListener(this);
    }

}