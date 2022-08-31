package jp.co.jimnet.kenshin.io.bluetooth;

import android.util.Log;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import jp.co.jimnet.kenshin.common.AppCharset;
import jp.co.jimnet.kenshin.common.CheckupItem;
import jp.co.jimnet.kenshin.config.setdata.ConfigData;
import jp.co.jimnet.kenshin.config.setdata.DeviceInfo;
import jp.co.jimnet.kenshin.io.ReadResultSize;
import jp.co.jimnet.kenshin.util.ByteUtil;
import jp.co.jimnet.kenshin.util.StringUtil;

public class MeasureDeviceConnector {

    private static final String TAG = "MeasureDeviceConnector";

    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_GET_DEVICE_FAILED = 1;       // 計測器取得失敗
    public static final int STATUS_DEVICE_UNSPECIFIED = 2;      // 計測器未選択
    public static final int STATUS_DEVICE_INCOMPATIBLE = 3;     // 計測器未対応
    public static final int STATUS_RECEIVE_VALUE_FAILED = 9;    // 計測値取得失敗

    public String[] receiveData = new String[14];
    public String[] receiveParam = new String[14];
    public int receiveRet;

    private int F_btRet;

    private ExecutorService runningExecutor;
    private BluetoothSppConnection connection;
    private final ConfigData configData;

    public MeasureDeviceConnector(ConfigData configData) {
        this.configData = configData;
    }

    public void stopReceive() {
        if (connection != null) {
            connection.cancel();
        }
        if (runningExecutor != null) {
            runningExecutor.shutdown();
        }
    }

    public int receiveDeviceValue(CheckupItem item, List<String> receiveBuffer) {
        return receiveDeviceValue(item, 0, new String[]{""}, receiveBuffer);
    }

    public int receiveDeviceValue(CheckupItem item, int subId, String[] params, List<String> receiveBuffer) {
        for (int i = 0; i < receiveParam.length; i++) {
            receiveParam[i] = i < params.length ? params[i] : "";
        }

        // 検査機器取得
        int deviceId = configData.getDeviceId(item.getFormId(), subId);
        if (deviceId == -1) {
            return STATUS_GET_DEVICE_FAILED;
        }


        // 各検査機器専用の関数を使用する
        connection = new BluetoothSppConnection(configData);
        Runnable deviceAction = getDeviceAction(connection, deviceId, item.getFormId());
        if (deviceAction == null) {
            return STATUS_DEVICE_INCOMPATIBLE;
        }
        runningExecutor = Executors.newSingleThreadExecutor();

        // deviceActionによって STATUS_SUCCESS に変更されない限り失敗扱い
        receiveRet = STATUS_RECEIVE_VALUE_FAILED;

        // スレッドを開始する
        Future<?> future = runningExecutor.submit(deviceAction);
        Log.d(TAG, "receiveDeviceValue: wait device action");
        try {
            // 終わるのを待つ
            future.get();
            Log.d(TAG, "receiveDeviceValue: finish device action");
        } catch (ExecutionException e) {
            e.printStackTrace();
            Log.d(TAG, "receiveDeviceValue: failed device action");
        } catch (InterruptedException e) {
            e.printStackTrace();
            Log.d(TAG, "receiveDeviceValue: interrupted device action");
        } finally {
            connection.close();
        }

        if (receiveRet == StatusConstants.RET_SUCCESS) {
            receiveBuffer.clear();
            for (int i = 0; i < receiveData.length; i++) {
                receiveBuffer.add(receiveData[i] != null ? receiveData[i].trim() : "");
            }
            Log.d(TAG, "receiveDeviceValue: receive=" + receiveBuffer);
            return STATUS_SUCCESS;
        }
        Log.d(TAG, "receiveDeviceValue: error (ret=" + receiveRet + ")");
        return receiveRet;
    }

    /**
     * SendDeviceValue()内の送信処理に相当
     *
     * @param deviceId
     * @param params
     * @return
     */
    public int sendDeviceValue(int deviceId, String[] params) {
        for (int i = 0; i < receiveParam.length; i++) {
            receiveParam[i] = i < params.length ? params[i] : "";
        }

        connection = new BluetoothSppConnection(configData);
        String address = configData.deviceInfo[deviceId].address;

        try {
            // 各検査機器機器専用の関数を使用する
            switch (deviceId) {
                case DeviceInfo.Index.DEV_VaSeraVS1000:  // --------------------------------- CAVI
                    // VaSera VS1000使用
                    return btVS1000(connection, address);
                case DeviceInfo.Index.DEV_VaSeraVS1500:
                    // VaSera VS1000使用
                    return btVS1500(connection, address);
                case DeviceInfo.Index.DEV_VaSeraVS3000Send:   // nihon 17/11/07 add
                    // VaSera VS3000使用
                    return btVS3000SEND(connection, address);
                case DeviceInfo.Index.DEV_ECG1450:  // -------------------------------------- 心電図
                    // ECG-1450使用
                    return btECG1450(connection, address);
                case DeviceInfo.Index.DEV_FCP4721:
                    // FCP4721使用
                    return btFCP4721(connection, address);
                case DeviceInfo.Index.DEV_FCP4521:
                    // FCP4521使用
                    return btFCP4521(connection, address);
                case DeviceInfo.Index.DEV_FUKUDAIMO:
                    // FUKUDA汎用使用
                    return btFUKUDA(connection, address);
                case DeviceInfo.Index.DEV_FUKUDAIMO2:
                    // FUKUDA汎用使用(健康医学ver.)  20/01/24
                    return btFUKUDA2(connection, address);
                case DeviceInfo.Index.DEV_FUKUDACMN:
                    // フクダ電子共通 22/03/01
                    //btFUKUDAと共通
                    return btFUKUDA(connection, address);
                case DeviceInfo.Index.DEV_Vigoment:  // ------------------------------------- ビゴメント
                    return btVIGOMENT(connection, address);
                case DeviceInfo.Index.DEV_FCP7541:   // -------------------------------------- 心電図  // 16/02/12
                    // FCP7541使用
                    return btFCP7541(connection, address);
                case DeviceInfo.Index.DEV_SREXD32C:  // -------------------------------------- 胃部Ｘ線 // 18/02/13
                    // SREXD32C使用
                    return btSREXD32C(connection, address);
                case DeviceInfo.Index.DEV_QRCONN:  // -------------------------------------- 眼底QR CONNECT VIGOMENT // 18/02/23
                    // QR CONNECT(VIGOMENT)使用
                    return btQRCONN(connection, address);
                case DeviceInfo.Index.DEV_QRCONNIMO: // ------------------------------------- 眼底QR CONNECT VIGOMENT + イメージワン // 20/02/03
                    // QR CONNECT(VIGOMENT)+イメージワン使用
                    return btQRCONN(connection, address);
                case DeviceInfo.Index.DEV_QRCONNDR:  // -------------------------------------- 胃部QR CONNECT VIGOMENT // 20/03/03
                    // QR CONNECT(VIGOMENT)使用
                    return btQRCONN(connection, address);
                default: // ---------------------------------------------- 該当関数なし
                    return 2;
            }
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private Runnable getDeviceAction(BluetoothSppConnection connection, int deviceId, int formId) {
        Log.d(TAG, "getDeviceAction: deviceId=" + deviceId);
        String address = configData.deviceInfo[deviceId].address;
        switch (deviceId) {
            case DeviceInfo.Index.DEV_AD6400:  // --------------------------------------- 身長・体重・体脂肪
                // AD-6400使用
                return () -> btAD6400(connection, address);
            case DeviceInfo.Index.DEV_AD6400W:
                // AD-6400使用
                return () -> btAD6400WithW(connection, address);
            case DeviceInfo.Index.DEV_TBF210:
                // TBF-210使用
                return () -> btTBF210(connection, address);
            case DeviceInfo.Index.DEV_TBF210Y:
                // TBF-210使用
                return () -> btTBF210Y(connection, address);
            case DeviceInfo.Index.DEV_WB110:
                // WB-110使用
                return () -> btWB110(connection, address);
            case DeviceInfo.Index.DEV_DC320:
                // DC-320使用
                return () -> btDC320PCM(connection, address);
            case DeviceInfo.Index.DEV_DC217:
                // DC-217 使用 2022/02/17 追加
                return () -> btDC217(connection, address);
            case DeviceInfo.Index.DEV_TBF102:
                // TBF-102使用
                return () -> btTBF102(connection, address);
            case DeviceInfo.Index.DEV_TBF310:
                // TBF-310使用
                return () -> btTBF310(connection, address);
            case DeviceInfo.Index.DEV_WB150:
                // WB-150使用
                return () -> btWB150(connection, address);
            case DeviceInfo.Index.DEV_BH100:
                // BH-100使用
                return () -> btBH100(connection, address);
            case DeviceInfo.Index.DEV_DC430:
                // DC430使用
                return () -> btDC430(connection, address);
            case DeviceInfo.Index.DEV_USM700GSI:  // ------------------------------------ 血圧計
                // USM700GSI使用
                return () -> btUSM700GSI(connection, address);
            case DeviceInfo.Index.DEV_RV2:
                // RV-2使用
                return () -> btRV2(connection, address);
            case DeviceInfo.Index.DEV_TM2655:
                // TM2655使用
                return () -> btTM2655(connection, address);
            case DeviceInfo.Index.DEV_RV3:
                // RV-3使用
                return () -> btRV3(connection, address);
            case DeviceInfo.Index.DEV_TM2580:
                // TM2580使用
                return () -> btTM2580(connection, address);
            case DeviceInfo.Index.DEV_AB29:  // ----------------------------------------- 聴力
                // AB-29使用
                receiveParam[1] = formId == 8 ? "0" : "1";
                return () -> btAB29(connection, address);
            case DeviceInfo.Index.DEV_CV20:  // ----------------------------------------- 視力
                // CV-20使用
                return () -> btCV20(connection, address);
            case DeviceInfo.Index.DEV_NV300:
                // NV-300使用
                return () -> btNV300(connection, address);
            case DeviceInfo.Index.DEV_SP350COPD:  // ------------------------------------ 肺活量計
                // SP-350COPD使用
                return () -> btSP350COPD(connection, address);
            case DeviceInfo.Index.DEV_FX3:
                // FX3使用
                return () -> btFX3(connection, address);
            case DeviceInfo.Index.DEV_SP750COPD:
                // SP-750COPD使用
                return () -> btSP750COPD(connection, address);
            case DeviceInfo.Index.DEV_HI701:
                return () -> btHI701(connection, address);
            case DeviceInfo.Index.DEV_SYSTEM7:
                // SYSTEM7使用
                return () -> btSYSTEM7(connection, address);
            case DeviceInfo.Index.DEV_NCT10:  // ---------------------------------------- 眼圧
                // NCT10使用
                return () -> btNCT10(connection, address);
            case DeviceInfo.Index.DEV_XpertPlus:
                // XpertPlus使用
                return () -> btXpertPlus(connection, address);
            case DeviceInfo.Index.DEV_CT70:
                // CT-70使用
                return () -> btCT70(connection, address);
            case DeviceInfo.Index.DEV_NCT200:
                // NCT200使用
                return () -> btNCT200(connection, address);
            case DeviceInfo.Index.DEV_TX20P:
                // TX-20P使用
                return () -> btTX20P(connection, address);
            case DeviceInfo.Index.DEV_AOS100NW:  // ------------------------------------- 骨密度
                // AOS-100NW使用
                return () -> btAOS100NW_S(connection, address);
            case DeviceInfo.Index.DEV_AOS100NW_ORG:
                // AOS-100NW使用(オリジナル新宿以外)
                return () -> btAOS100NW(connection, address);
            case DeviceInfo.Index.DEV_CM200:
                // CM200仕様
                return () -> btCM200(connection, address);
            case DeviceInfo.Index.DEV_DCS600EX:
                // DCS600EX使用
                return () -> btDCS600EX(connection, address);
            case DeviceInfo.Index.DEV_DTM15B:  // ----------------------------------------- 腹囲
                // DTM15使用
                return () -> btDTM15(connection, address);
            case DeviceInfo.Index.DEV_DC250T:   // --------------------------------------- 身長・体重・体脂肪
                // DC-250使用
                return () -> btDC250T(connection, address);
            case DeviceInfo.Index.DEV_DC250:    // --------------------------------------- 身長・体重・体脂肪 PCモード
                // DC-250使用(PCモード)
                return () -> btDC250PCM(connection, address);
            case DeviceInfo.Index.DEV_VaSeraVS3000Recv:   // ---------------------------- CAVI
                // VaSera VS3000使用
                return () -> btVS3000RECV(connection, address);
            case DeviceInfo.Index.DEV_R800:    // ---------------------------------------- オートレフ 屈折検査
                // ACCUREF R800使用
                return () -> btACCUREF_R800(connection, address);
            case DeviceInfo.Index.DEV_RKF2:
                // RK-F2使用
                return () -> btRKF2(connection, address);
            default:
                return null;
        }
    }

    private void clearReceiveValues() {
        receiveRet = StatusConstants.RET_ERR_MEASURE;
        for (int i = 1; i < receiveData.length; i++) {
            receiveData[i] = "";
        }
    }

    private void setReceiveRetWithBtRet(int btRet) {
        switch (btRet) {
            case StatusConstants.BT_SUCCESS:
                receiveRet = StatusConstants.RET_SUCCESS;       // 正常終了
                break;
            case StatusConstants.BT_FAILED:
                receiveRet = StatusConstants.RET_ERR_DEVFAILED; // デバイス情報設定失敗
                break;
            case StatusConstants.BT_DRIVER_ERROR:
                receiveRet = StatusConstants.RET_ERR_DRIVER;    // ドライバーエラー
                break;
            case StatusConstants.BT_FUNCTION_UNSUPPORT:
                receiveRet = StatusConstants.RET_ERR_UNSUPPORT; // 未サポートエラー
                break;
        }
    }

    // region Bluetooth連携機器個別の受信処理

    private void btAD6400(BluetoothSppConnection connection, String address) {
    }

    private void btAD6400WithW(BluetoothSppConnection connection, String address) {
    }

    /**
     * TBF-210 身体計
     */
    private void btTBF210(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        try {
            // 出力項目のクリア
            clearReceiveValues();
            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                // 測定結果取得
                btRet = connection.receiveDeviceValue(status, resultSize);
                System.out.println("btRet:::値:::" + btRet);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "TBF-210 身体計】［receiveRet］受信キャンセル" + receiveRet);
                    return;
                }

                /*
                String value_01 = ByteUtil.bytesToString(status, 34 - 1, 7).trim(); // 身長
                String value_02 = ByteUtil.bytesToString(status, 42 - 1, 7).trim(); // 体重
                String value_03 = ByteUtil.bytesToString(status, 55 - 1, 4).trim(); // 体脂肪

                 */

                double value_01_d = ByteUtil.bytesToDouble(status, 34 - 1, 7); // 身長
                double value_02_d = ByteUtil.bytesToDouble(status, 42 - 1, 7); // 体重
                double value_03_d = ByteUtil.bytesToDouble(status, 55 - 1, 4); // 体脂肪

                // 四捨五入
                /*
                value_01_d = Math.round(value_01_d);
                value_02_d = Math.round(value_02_d);
                value_03_d = Math.round(value_03_d);

                 */

                // =============== dobule => String パース & 桁数を少数点切り捨て
                /*
                String value_01 = String.format("%,.0f",value_01_d);
                String value_02 = String.format("%,.0f",value_02_d);
                String value_03 = String.format("%,.0f",value_03_d);

                 */

                String value_01 = String.valueOf(value_01_d);
                String value_02 = String.valueOf(value_02_d);
                String value_03 = String.valueOf(value_03_d);


                if (StringUtil.isNumeric(value_01) &&
                        StringUtil.isNumeric(value_02) &&
                        StringUtil.isNumeric(value_03)) {

                    // 値格納
                    receiveData[1] = value_01.trim(); // 身長,値
                    receiveData[2] = value_02.trim(); // 体重,値

                    //============= 体脂肪
                    String taishibou_value = value_03.trim();
                    if (taishibou_value.equals("0.0") || taishibou_value.equals("0")) {
                        taishibou_value = "";
                        receiveData[3] = taishibou_value; // 体脂肪,値
                    } else {
                        receiveData[3] = taishibou_value; // 体脂肪,値
                    }

                    Log.d(TAG, "btTBF210: 値格納::: = " + receiveData[1]
                            + ":::" + receiveData[2] + ":::" + receiveData[3]);
                }

            }
            // 正常終了
            setReceiveRetWithBtRet(btRet);

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }

    }

    private void btTBF210Y(BluetoothSppConnection connection, String address) {
    }

    /**
     *  体重計（WB-110） から測定値を取得する
     */
    private void btWB110(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] byBuff;                                      // 送信バッファ
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        String strCommand = "";    // 送信パラメータ用
        String strResult = "";

        try {

            // 出力項目のクリア
            clearReceiveValues();

            char vb_Cr = 0x0d; // vbCr(13)
            char vb_Lf = 0x0a; // vbLf(10)
            // 測定開始コマンド
            strCommand = "DR" + String.valueOf(vb_Cr) + String.valueOf(vb_Lf);

            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                try {

                    //=== do - while 開始
                    do {
                        // バイトコードへ変換
                        byBuff = strCommand.getBytes(AppCharset.SHIFT_JIS);

                        // Y.O パリティ　付加
                        SetParity(byBuff, byBuff.length, 0);

                        // コマンド送信
                        connection.send(byBuff, byBuff.length);
                        // 測定結果受信
                        btRet = connection.receiveDeviceValue(status, resultSize);

                        // パリティ除去
                        if (RemoveParity(status, resultSize.get(), 0) == false) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btWB110: [Recv] 異常終了 = パリティ 外す " + btRet);
                            return;
                        }

                        strResult = ByteUtil.bytesToString(status, 0, resultSize.get());

                        if (strResult.length() <= 0) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btWB110: [Recv] 異常終了  = " + strResult);
                            return;
                        }

                        if (ByteUtil.bytesToString(status, 0, 2).equals("S ")) {
                            if (BtCDbl(strResult.substring(4,10)) > 0) {
                                // 測定結果格納
                                receiveData[2] = BtEdit(strResult.substring(4,10));
                                break;
                                /*  2022/03/15 0.0Kgのときにエラーになるのでコメントアウト
                            } else {
                                //  receiveData[2] = BtEdit(strResult.substring(1, 10));
                                receiveData[2] = BtEdit(strResult.substring(0, 9));
                                // キャンセル
                                receiveRet = StatusConstants.RET_ERR_CANCEL;
                                Log.d(TAG, "btWB110: [Recv] 受信キャンセル else 1:::  = " +  receiveData[2]);
                                return;
                                 */
                            }
                        } else {
                            /*
                            receiveData[2] = BtEdit(strResult.substring(0, 9));
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;

                             */
                            // 2022/03/13 修正
                            // Log.d(TAG, "btWB110: [Recv] 受信キャンセル else 2 コメントアウト:::  = " +  receiveData[2]);
                            // return;
                        }

                    } while (true);

                } finally {
                    // 仮想シリアルポート切断
                    setReceiveRetWithBtRet(btRet);
                }  // ====== END try

            } else {
                // ========= エラー
                receiveRet = StatusConstants.RET_ERR_MEASURE;
                return;
            } //===================== END IF =====================

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }

    }

    /**
     * 体組成計 (DC-320)
     */
    private void btDC320PCM(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        int btRet;
        int intExitFlag;
        int i;
        String strCommand = null;
        String strAge = null;
        String strData;
        String str_CrLf;

        String pMode = null; // '1.検査モード
        String pSin = null;  // '2.身長
        String pSbt = null;  // '3.性別
        String pAge = null;  // '4.年齢

        try {

            pMode = receiveParam[1]; // 検査モード
            pSin = receiveParam[2];  // 身長
            pSbt = receiveParam[3];  // 性別
            pAge = receiveParam[4];  // 年齢

            // 出力項目のクリア
            clearReceiveValues();

            // コマンド
            // CRLF 文字列作成
            char C_Cr = 0x0d; // Cr
            char C_Lf = 0x0a; // Lf
            strCommand = String.valueOf(C_Cr) + String.valueOf(C_Lf);

            // CRLF 文字列作成
            char J_C_Cr = 0x0d; // Cr
            char J_C_Lf = 0x0a; // Lf
            str_CrLf = String.valueOf(J_C_Cr) + String.valueOf(J_C_Lf);

            // パラメーターチェック
            // 年齢　（３桁を２桁に）
            if (!(pAge.equals(""))) {
                // pAge を int型へ　パース
                int i_pAge = Integer.parseInt(pAge);
                System.out.println("i_pAge 値:::" + i_pAge);

                if (i_pAge != 0) {
                    // 桁数変更 3 => 2
                    strAge = String.format("%.2s", pAge);
                    Log.d(TAG, "【 BtDC320PCM 】 (Recv): strAge = " + strAge);
                } else {
                    // 受付不可
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "【 BtDC320PCM 】 (Recv): パラメーター年齢エラー = " + receiveRet);
                    return;
                }

            } else {
                strAge = "30";
            }

            // シリアルポート接続
            btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                intExitFlag = 0;
                System.out.println("intExitFlag ::: 値" + intExitFlag);

                // ======= ①　PC　モードへ切り替え
                while (intExitFlag == 0) {

                    char c_M = 0x4d; // Cr
                    char c_1 = 0x31; // Lf

                    String strCommand_Temp = String.valueOf(c_M) + String.valueOf(c_1) + strCommand;

                    // 追加
                    // byte[] byBuff = strCommand_Temp.getBytes(AppCharset.SHIFT_JIS);
                    byte[] byBuff = strCommand_Temp.getBytes();

                    Log.d(TAG, "体組成計 (DC-320): byBuff = " + byBuff.toString() + new String(byBuff));

                    // === コマンド　送信 ===
                    connection.send(byBuff, byBuff.length);

                    // === 測定結果　受信 ===
                    btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);
                    Log.d(TAG, "体組成計 (DC-320)】①　PC　モードへ切り替え 取得データ：：： btRet = " + btRet);

                    if (resultSize.get() <= 0) {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-320): キャンセル = " + receiveRet);
                        return;
                    }

                    // 値取得
                    strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                    Log.d(TAG, "体組成計 (DC-320)】①　PC　モードへ切り替え 取得データ：：： strData = " + strData);

                    String strData_LEFT = strData.substring(0, 1);
                    if (strData_LEFT.equals("@")) {
                        intExitFlag = 1;

                    } else {
                        // 受付不可
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-320)】［Recv］受付不可 ①　PC　モードへ切り替え");
                        return;
                    }

                } // ======   END While

                // ============ ② 風袋量設定 （1kg 固定）
                intExitFlag = 0;

                while (intExitFlag == 0) {

                    // バイトコードへ変換
                    String strCommand_Temp_02 = "D001.0" + strCommand;
                    //   byte[] byBuff = strCommand_Temp_02.getBytes();
                    // 追加
                    byte[] byBuff = strCommand_Temp_02.getBytes(AppCharset.SHIFT_JIS);

                    // === コマンド　送信 ===
                    connection.send(byBuff, byBuff.length);

                    // === 測定結果　受信 ===
                    btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                    if (resultSize.get() <= 0) {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-320): キャンセル = " + receiveRet);
                        return;
                    }

                    // 値取得
                    strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                    Log.d(TAG, "体組成計 (DC-320)】取得データ ②（風袋量設定）：：： strData = " + strData);

                    String strData_Temp = strData.substring(0, 9);
                    if (strData_Temp.equals("D0,Pt,1.0")) {
                        intExitFlag = 1;

                    } else {
                        // 受付不可
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-320)】［Recv］受付不可 ② 風袋量設定 （1kg 固定）");
                        return;
                    }

                } // ======   END While

                // ================== 体脂肪モードの場合 ==================
                if (pMode.equals("1")) {

                    // ============ ③ 性別設定
                    intExitFlag = 0;
                    while (intExitFlag == 0) {

                        // バイトコード変換
                        String strCommand_Temp = "D1" + pSbt + strCommand;
                        //     byte[] byBuff = strCommand_Temp.getBytes();

                        // 追加
                        byte[] byBuff = strCommand_Temp.getBytes(AppCharset.SHIFT_JIS);

                        // === コマンド　送信 ===
                        connection.send(byBuff, byBuff.length);
                        Log.d(TAG, "体組成計 (DC-320)】取得データ（性別設定）：：： コマンド　送信 = " + byBuff.toString() +
                                "値" + new String(byBuff));

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        if (resultSize.get() <= 0) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320): キャンセル = " + receiveRet);
                            return;
                        }

                        // 値取得
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "体組成計 (DC-320)】取得データ ③（性別設定）：：： strData = " + strData);

                        String strData_Temp = strData.substring(0, 6);
                        if (strData_Temp.equals("D1,GE,")) {
                            intExitFlag = 1;
                        } else {
                            // 受付不可
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320)】［Recv］受付不可  ③ 性別設定");
                            return;
                        }

                    } // ======   END While

                    // ============ ④ 体型設定（スタンダード固定）
                    intExitFlag = 0;
                    while (intExitFlag == 0) {

                        String Temp_strCommand = "D20" + strCommand;
                        byte[] byBuff = Temp_strCommand.getBytes(AppCharset.SHIFT_JIS);
                        // コマンド送信
                        connection.send(byBuff, byBuff.length);

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);
                        // 値取得
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());

                        String LEFT_strData = strData.substring(0, 7);
                        Log.d(TAG, "体組成計 (DC-320)】取得データ ④（体型設定）：：： LEFT_strData = " + strData);
                        if (LEFT_strData.equals("D2,Bt,0")) {
                            intExitFlag = 1;

                        } else {
                            // 受付不可
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320)】［Recv］受付不可 ④ 体型設定（スタンダード固定）");
                            return;
                        }

                    } // ====== End while

                    // ============ ⑤ 身長設定
                    intExitFlag = 0;
                    while (intExitFlag == 0) {
                        // バイトコードへ変換
                        String Temp_strCommand = "D3" + pSin + strCommand;

                        // 追加
                        byte[] byBuff = Temp_strCommand.getBytes(AppCharset.SHIFT_JIS);
                        //    byte[] byBuff = Temp_strCommand.getBytes();
                        // コマンド送信
                        connection.send(byBuff, byBuff.length);

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        if (resultSize.get() <= 0) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320): キャンセル = ⑤ 身長設定 " + receiveRet);
                            return;
                        }

                        // 値取得
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());

                        String Temp_strData = strData.substring(0, 6);
                        Log.d(TAG, "体組成計 (DC-320)】取得データ ⑤（身長設定）：：： Temp_strData = " + Temp_strData);
                        if (Temp_strData.equals("D3,Hm,")) {
                            intExitFlag = 1;

                        } else {
                            // 受付不可
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320)】［Recv］⑤ 身長設定");
                            return;
                        }

                    } // ====== End while

                    // ============ ⑥ 年齢設定
                    intExitFlag = 0;
                    while (intExitFlag == 0) {
                        // バイトコードへ変換
                        String Temp_strCommand = "D4" + pAge + strCommand;

                        // 追加
                        byte[] byBuff = Temp_strCommand.getBytes(AppCharset.SHIFT_JIS);
                        // コマンド送信
                        connection.send(byBuff, byBuff.length);

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        if (resultSize.get() <= 0) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320): キャンセル =  ⑥ 年齢設定 " + receiveRet);
                            return;
                        }

                        // 値取得
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());

                        String Temp_strData = strData.substring(0, 6);
                        Log.d(TAG, "体組成計 (DC-320)】取得データ（年齢設定）：：： Temp_strData = " + Temp_strData);
                        if (Temp_strData.equals("D4,AG,")) {
                            intExitFlag = 1;

                        } else {
                            // 受付不可
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320)】［Recv］⑥ 年齢設定");
                            return;
                        }

                    } // ====== End while

                    // ============ ⑦ 一括測定
                    intExitFlag = 0;
                    while (intExitFlag == 0) {
                        // バイトコードへ変換
                        String Temp_strCommand = "G0" + strCommand;

                        // 追加
                        byte[] byBuff = Temp_strCommand.getBytes(AppCharset.SHIFT_JIS);
                        // コマンド送信
                        connection.send(byBuff, byBuff.length);

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        if (resultSize.get() <= 0) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320): キャンセル =  ⑦ 一括測定 G0 ::: " + receiveRet);
                            return;
                        }

                        // 値取得
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());

                        String strData_LEFT = strData.substring(0, 1);
                        Log.d(TAG, "体組成計 (DC-320)】取得データ（一括測定）：：： strData_LEFT = " + strData_LEFT);
                        if (strData_LEFT.equals("@")) {
                            intExitFlag = 1;

                        } else {
                            // 受付不可
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320)】［Recv］⑦ 一括測定");
                            return;
                        }

                    } // ====== End while

                    // ============ ⑧ 結果待ち
                    intExitFlag = 0;
                    while (intExitFlag == 0) {

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        if (resultSize.get() <= 0) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320): キャンセル =  ⑧ 結果待ち " + receiveRet);
                            return;
                        }

                        // 値取得
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());

                        String Temp_strData = strData.substring(0, 5);
                        if (Temp_strData.equals("{0,16")) {
                            intExitFlag = 1;
                        }

                    } // ====== End while

                } else {
                    // ======================== 体重計モードの場合 ========================
                    // ====== ③ 体重計測定
                    intExitFlag = 0;
                    Log.d(TAG, "体組成計 (DC-320): intExitFlag = 体重計モード 開始 **************** " + intExitFlag);

                    while (intExitFlag == 0) {
                        // バイトコードへ変換
                        String Temp_strCommand = "F0" + strCommand;

                        // 追加
                        byte[] byBuff = Temp_strCommand.getBytes(AppCharset.SHIFT_JIS);
                        // コマンド送信
                        connection.send(byBuff, byBuff.length);

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        if (resultSize.get() <= 0) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320): キャンセル = 体重計モード ③ 体重計測定 " + receiveRet);
                            return;
                        }

                        // 値取得
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());

                        String Temp_strData = strData.substring(0, 1);
                        if (Temp_strData.equals("@")) {
                            intExitFlag = 1;

                        } else {
                            // 受付不可
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320)】［Recv］ 体重計モード ③ 体重計測定 受付不可");
                            return;
                        }
                    } // ======= END while

                    // ④ 結果待ち
                    intExitFlag = 0;
                    while (intExitFlag == 0) {

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        if (resultSize.get() <= 0) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "体組成計 (DC-320): キャンセル =  ④ 結果待ち " + receiveRet);
                            return;
                        }

                        // 値取得
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "体組成計 (DC-320): strData 979行目 値取得::: " + strData);

                        String Temp_strData = strData.substring(0, 6);
                        Log.d(TAG, "体組成計 (DC-320): strData 982行目 値取得::: " + Temp_strData);
                        if (Temp_strData.equals("F0,Wk,")) {
                            intExitFlag = 1;
                        }

                    } // ====== End while

                } //=============================== END if

                // 測定結果格納
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "体重　値 (WK,) strData::: i の値 " + strData);

                // ====== 体重
                i = strData.indexOf("Wk,"); // C を取得
                Log.d(TAG, "体重　値 (WK,) idnexOf::: i の値 " + i);
                Log.d(TAG, "体重　値 (WK,) strData の値 " + strData);

                if (i >= 0) {
                    if (strData.substring(i + 3 + 4, i + 3 + 5).equals(",")) {
                        receiveData[2] = String.valueOf(Double.parseDouble(strData.substring(i + 3, i + 3 + 4)));
                        Log.d(TAG, "表示用　「体重」値取得 (WK,) receiveData[2] substring(i + 3, i + 3 + 4) :::" +
                                "" + receiveData[2] );
                    } else {
                        receiveData[2] = String.valueOf(Double.parseDouble(strData.substring(i + 3, i + 3 + 5)));
                        Log.d(TAG, "表示用　「体重」値取得 (WK,) receiveData[2]  substring(i + 3, i + 3 + 5) :::" +
                                receiveData[2] );
                    }

                } else {
                    receiveData[2] = "0";
                } // ====== End if

                // ====== 体脂肪
                i = strData.indexOf("FW,");
                Log.d(TAG, "体脂肪　値 (FW,) idnexOf::: i の値 " + i);
                Log.d(TAG, "体脂肪　値 (FW,) strData の値 " + strData);
                if (i >= 0) {

                    if (strData.substring(i + 3 + 3, i + 3 + 4).equals(",")) {
                        receiveData[3] = String.valueOf(Double.parseDouble(strData.substring(i + 3, i + 3 + 3)));
                        Log.d(TAG, "表示用　「体脂肪」値取得 (FW,) receiveData[2] substring(i + 3, i + 3 + 3) :::" +
                                "" + receiveData[3] );
                    } else {
                        receiveData[3] = String.valueOf(Double.parseDouble(strData.substring(i + 3, i + 3 + 4)));
                        Log.d(TAG, "表示用　「体脂肪」値取得 (FW,) receiveData[2] substring(i + 3, i + 3 + 4) :::" +
                                "" + receiveData[3] );
                    }

                } else {
                    receiveData[3] = "0";
                }

                // ========= 検査チェック
                if (StringUtil.isNumeric(receiveData[2]) == false) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "体組成計 (DC-320):receiveData[2] 異常終了 =   検査チェック "
                            + receiveData[2]);
                    return;
                }

                if (StringUtil.isNumeric(receiveData[3]) == false) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "体組成計 (DC-320):receiveData[3] 異常終了 =   検査dcチェック "
                            + receiveData[2]);
                    return;
                }

                // 正常終了
                setReceiveRetWithBtRet(btRet);
            } else {
                // キャンセル
                receiveRet = StatusConstants.RET_ERR_CANCEL;
                Log.d(TAG, "体組成計 (DC-320):receiveData[3] BT_SUCCESS エラー:::  = ");
                return;

            }

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }
    }

    private void btTBF102(BluetoothSppConnection connection, String address) {
    }

    /**
     *  TBF-310  「体重」、「体脂肪率　取得」
     */
    private void btTBF310(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] byBuff;                                      // 送信バッファ
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        String strCommand = "";    // 送信パラメータ用
        String strResult = "";

        try {
            // 出力項目のクリア
            clearReceiveValues();

            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                try {

                    do {
                        // バイトコードへ変換
                        byBuff = strCommand.getBytes(AppCharset.SHIFT_JIS);
                        // 測定結果取得
                        btRet = connection.receiveDeviceValue(status, resultSize);
                        System.out.println("btRet:::値:::" + btRet);

                        // パリティ除去
                        if(RemoveParity(status, resultSize.get(), 0) == false) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "TBF-310: [Recv] 異常終了 = パリティ 外す " + btRet);
                            return;
                        }

                        strResult = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "TBF-310 】strResult 値:::" + strResult);

                        if (strResult.length() <= 0) {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "TBF-310: [Recv] 異常終了  = " + strResult);
                            return;
                        }

                        String [] Arr_Result = strResult.split(",");

                        for(String arr_string : Arr_Result) {
                            Log.d(TAG, "TBF-310 】 for arr_string 値:::" + arr_string);
                        }

                        // ========= 「体重」 「体脂肪」を取得
                        String value_01 = Arr_Result[7 - 1];
                        String value_02 = Arr_Result[9 - 1];

                        Log.d(TAG, "TBF-310 】value_01 値:::" + value_01);
                        Log.d(TAG, "TBF-310 】value_02 値:::" + value_02);

                        // ========= 「体重」 「体脂肪」を セットする
                        if(!value_01.isEmpty() || !value_02.isEmpty()) {

                            receiveData[2] = value_01; // 体重セット
                            receiveData[3] = value_02; // 体脂肪セット

                            Log.d(TAG, "TBF-310 】receiveData[2] 値:::" + receiveData[2]);
                            Log.d(TAG, "TBF-310 】receiveData[3] 値:::" + receiveData[3]);

                            break;
                        } else {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btTBF310: [Recv] 受信キャンセル　（値取得 失敗） else 1:::  = " + receiveData[2]);
                            break;
                        }

                    } while(true);

                } finally {
                    // 仮想シリアルポート切断
                    setReceiveRetWithBtRet(btRet);
                }  // ====== END try

            } else {

                // キャンセル
                receiveRet = StatusConstants.RET_ERR_CANCEL;
                Log.d(TAG, "btTBF310: [Recv] BT_SUCCESS エラー:::  = ");
                return;
            }

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }

    }

    /***
     *  体重計　WB-150）から測定値を取得する
     */
    private void btWB150(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 2000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ
        Integer i, j;
        String strResult;

        try {
            // 出力項目のクリア
            clearReceiveValues();

            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                do {
                    // 測定結果受信
                    btRet = connection.receiveDeviceValue(status, resultSize);

                    if (resultSize.get() <= 0) {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "btWB150: BtWB150】［Recv］受信キャンセル");
                        return;
                    }

                    if (ByteUtil.bytesToString(status, 0, 5).equals("{0,16")) {
                        break;
                    }

                } while (true);

                // 測定結果格納
                // ? => 多分　get() で  resultSize.get() で長さを取得 ？
                strResult = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "btWB150: BtWB150】［strResult］測定結果格納" + strResult);

                // 体重
                i = strResult.indexOf("Wk,");
                if (i != 0) {
                    j = strResult.indexOf(",Pt,");
                    double dTmp = Double.parseDouble(strResult.substring(i + 3, j));
                    dTmp = ((double) Math.round(dTmp * 10)) / 10;
                    receiveData[2] = String.valueOf(dTmp);
                    Log.d(TAG, "btWB150: BtWB150】［receiveData[2]］体重 値取：：：" + receiveData[2]);
                } else {
                    receiveData[2] = "0";
                }
            }
            // 正常終了
            setReceiveRetWithBtRet(btRet);
        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }
    }

    /***
     *  身長計　BH-100から測定値を取得する
     */
    private void btBH100(BluetoothSppConnection connection, String address) {
        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ
        String value_01;

        try {
            // 出力項目のクリア
            clearReceiveValues();
            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                // 測定結果取得
                btRet = connection.receiveDeviceValue(status, resultSize);
                System.out.println("btRet:::値:::" + btRet);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "BH-100 身長】［receiveRet］受信キャンセル" + receiveRet);
                    return;
                }
                //{0,16,~1,1,MO,"BH-100",DT,"2019/04/06",TI,"13:06",Hm,158.9,CS,99
                if (ByteUtil.bytesToString(status, 58 - 1, 1).equals(",")) {
                    value_01 = ByteUtil.bytesToString(status, 54 - 1, 4); // 身長
                } else {
                    value_01 = ByteUtil.bytesToString(status, 54 - 1, 5); // 身長
                }

                if (StringUtil.isNumeric(value_01)) {
                    // 値格納
                    receiveData[1] = value_01; // 身長,値
                    Log.d(TAG, "btBH100: 値格納::: = " + receiveData[1]);
                }
            }
            // 正常終了
            setReceiveRetWithBtRet(btRet);
        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }
    }

    private void btUSM700GSI(BluetoothSppConnection connection, String address) {
    }

    private void btRV2(BluetoothSppConnection connection, String address) {
    }

    /**
     *  MODULE    ：BtTM2655
     *  概要      ：血圧計（TM2655）から測定値を取得する
     *  引数      ：変数名     属性            I/O  項目名
     *
     *  設定項目  ：ReceiveData(1)  最高血圧値
     *             ReceiveData(2)  最低血圧値
     *             ReceiveData(3)  脈拍数
     *
     */
    private void btTM2655(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] byBuff;	// 送信バッファ
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ VB => (intRecvSize)

        String strCommandG;     // 測定開始コマンド
        String strCommandC;     // 測定キャンセル・コマンド

        int intRet;

        try {

            // 出力項目のクリア
            clearReceiveValues();

            // 測定開始コマンド
            // &H16 = 22（10進） => SYN（同期）
            char syn_c = 0x16; // Chr(&H16)
            char soh_c = 0x01; // Chr(1) ヘッダ開始        SOH
            char stx_c = 0x02; // Chr(2) テキスト開始      STX
            char etx_c = 0x03; // Chr(3) テキスト終了      ETX
            String str_bcc_start = String.valueOf(soh_c) + "00" + String.valueOf(stx_c) + "ST" + String.valueOf(etx_c);
            strCommandG = String.valueOf(syn_c) + String.valueOf(syn_c) + appendBCC(str_bcc_start,1);

            // 測定キャンセルコマンド
            String str_bcc_stop = String.valueOf(soh_c) + "00" + String.valueOf(stx_c) + "SP" + String.valueOf(etx_c);
            strCommandC =  String.valueOf(syn_c) + String.valueOf(syn_c) + appendBCC(str_bcc_stop, 1);

            // マスターモードで仮想シリアルポート接続
            intRet = connection.connect(address);

            if (intRet == StatusConstants.BT_SUCCESS) {

                // コマンド送信
                byBuff = strCommandG.getBytes(AppCharset.SHIFT_JIS);
                connection.send(byBuff, byBuff.length);

                // 測定結果 受信
                intRet = connection.receiveDeviceValue(status, resultSize);
                Log.d(TAG, "TM2655: 測定結果受信 1 = " + intRet);

                // String Status_Tmp = Arrays.toString(status);
                String Status_Tmp = new String(status);
                Log.d(TAG, "TM2655: 測定結果受信 Status_Tmp ::: = " + Status_Tmp);
                // char si_c = 0x0f; // シフトIN
                char si_c = 0x15; // NAK (受信NG)
                String si_str = String.valueOf(si_c);

                // VB ソース
                //  If byStatus(6 - 1) = &H15 Then
                if (Status_Tmp.substring(5,6).equals(si_str)) {
                    // バイトコードへ変換
                    byBuff = strCommandC.getBytes(AppCharset.SHIFT_JIS);
                    // コマンド送信  （キャンセルコマンド）
                    connection.send(byBuff, byBuff.length);
                    Log.d(TAG, "【BtTM2655】［Recv］受信キャンセル" + byBuff);
                    return;
                }

                // 検査後自動受信される
                intRet = connection.receiveDeviceValue(status, resultSize);
                Log.d(TAG, "TM2655: 測定結果受信 intRet ::: = " + intRet);
                Log.d(TAG, "TM2655: 測定結果受信 resultSize.get() ::: = " + resultSize.get());
                // VB ソース
                // If (intRecvSize <= 0) Or (byStatus(6 - 1) = &H15) Then
                if (resultSize.get() <= 0 ||Status_Tmp.substring(5,6).equals(si_str)) {

                    // バイトコードへ変換
                    byBuff = strCommandC.getBytes(AppCharset.SHIFT_JIS);
                    // コマンド送信 （キャンセルコマンド）
                    connection.send(byBuff, byBuff.length);
                }

                // ========= 測定結果格納 =========
                // vb ソース　 If oEnc.GetString(byStatus, 30 - 1, 3) = "E00" Then
                String val_Tmp = ByteUtil.bytesToString(status, 30 - 1, 3);
                Log.d(TAG, "val_Tmp :::" + val_Tmp);

                if(ByteUtil.bytesToString(status, 30 - 1, 3).equals("E00")) {

                    String value_01 = ByteUtil.bytesToString(status, 35 - 1, 3).trim(); // 最高 血圧値
                    receiveData[1] = String.valueOf(Integer.parseInt(value_01));
                    Log.d(TAG, "receiveData[1] :::" + receiveData[1]);

                    String value_02 = ByteUtil.bytesToString(status, 45 - 1, 3).trim(); // 最低 血圧値
                    receiveData[2] = String.valueOf(Integer.parseInt(value_02));
                    Log.d(TAG, "receiveData[2] :::" + receiveData[2]);

                    String value_03 = ByteUtil.bytesToString(status, 50 - 1, 3).trim(); // 脈拍数
                    receiveData[3] = String.valueOf(Integer.parseInt(value_03));
                    Log.d(TAG, "receiveData[3] :::" + receiveData[3]);

                    // 正常終了
                    setReceiveRetWithBtRet(intRet);

                } else {

                    // 異常終了
                    Log.d(TAG, "【BtTM2655】［Recv］異常終了 " + intRet);
                    return;
                }

            }

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        } finally {
            //  '仮想シリアルポート切断()
            connection.close();
        }

    }

    private void btRV3(BluetoothSppConnection connection, String address) {
    }

    /**
     * 血圧計（TM2580）
     */
    private void btTM2580(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;

        ArrayList<Byte> listBytes = new ArrayList<Byte>();

        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ VB => (intRecvSize)
        String strCommandG = null; // 測定開始 コマンド

        try {
            // 出力項目のクリア
            clearReceiveValues();
            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);

            // 測定開始コマンド　01
            /*
            char a_5 = '5';
            char a_3 = '3';
            strCommandG = a_5 + "S" + a_3;
             */


            // 測定開始コマンド　02
            /*
            int a_5 = 5;
            int a_3 = 3;
            String a_5c = Character.toString((char) a_5);
            String a_3c = Character.toString((char) a_3);
            Log.d(TAG, "btTM2580:測定開始コマンド a_5c  = " + a_5c);
            Log.d(TAG, "btTM2580:測定開始コマンド a_3c  = " + a_3c);
            strCommandG = a_5c + "S" + a_3c;
            */

            /*
            // 測定開始コマンド 03
            String a_5 = "ENQ"; // 5
            String a_3 = "ETX"; // 3
            strCommandG = a_5 + "S" + a_3;
            */

            /*
            char[] a_5 = Character.toChars(0x35);
            char[] a_3 = Character.toChars(0x33);

             */

            // 測定開始コマンド 04  16進数
            byte buffer[] = new byte[3];
            buffer[0] = 0x05; // 5
            buffer[1] = 0x53; // "S"
            buffer[2] = 0x03; // 3

            Log.d(TAG, "btTM2580:測定開始コマンド  = " + Arrays.toString(buffer));

            if (btRet == StatusConstants.BT_SUCCESS) {

                // バイトコードへ変換　
                //   byte[] buffer = strCommandG.getBytes(AppCharset.SHIFT_JIS);
                /*
                byte[] bufferx = strCommandG.getBytes(AppCharset.SHIFT_JIS);
                Log.d(TAG, "btTM2580:(バイト変換)" + buffer);

                 */

                // バイトの長さ取得
                int buffer_length = buffer.length;

                // コマンド送信　
                connection.send(buffer, buffer_length);

                // 測定結果受信
                btRet = connection.receiveDeviceValue(status, resultSize);
                Log.d(TAG, "btTM2580: 測定結果受信 = " + btRet);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "btTM2580: キャンセル = " + receiveRet);
                    return;
                }

                String value_01 = ByteUtil.bytesToString(status, 17 - 1, 3).trim(); // 最高血圧
                String value_02 = ByteUtil.bytesToString(status, 26 - 1, 3).trim(); // 最低血圧
                String value_03 = ByteUtil.bytesToString(status, 30 - 1, 3).trim(); // 脈拍数

                if (StringUtil.isNumeric(value_01) &&
                        StringUtil.isNumeric(value_02) &&
                        StringUtil.isNumeric(value_03)) {

                    // 値格納
                    receiveData[1] = String.valueOf(Integer.parseInt(value_01));
                    receiveData[2] = String.valueOf(Integer.parseInt(value_02));
                    receiveData[3] = String.valueOf(Integer.parseInt(value_03));

                    Log.d(TAG, "btTM2580: 値格納::: = " + receiveData[1]
                            + ":::" + receiveData[2] + ":::" + receiveData[3]);

                } else {
                    receiveRet = StatusConstants.RET_ERR_MEASURE;
                    return;
                }

            }
            // 正常終了
            setReceiveRetWithBtRet(btRet);

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        } finally {
            //  '仮想シリアルポート切断()
            connection.close();
        }

    }

    private void btAB29(BluetoothSppConnection connection, String address) {
    }

    private void btCV20(BluetoothSppConnection connection, String address) {
    }

    private void btNV300(BluetoothSppConnection connection, String address) {
    }

    /**
     * 肺活量計（SP-350COPD）から測定値を取得する
     */
    private void btSP350COPD(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 3000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        int btRet = 0;

        byte[] byBuff;

        String strCommandG = "";  // 属性データ送信用
        String strSendAck = "";   //
        String strSendCan = "";   //

        String strData = "";

        String pNO = "";
        String pName = "";
        String pSex = "";
        String pAge = "";
        String pSng = "";
        String pHeight = "";
        String pWeight = "";

        boolean bCancelFlag;

        int i;

        try {

            pNO = receiveParam[1];     // '1.検査番号
            pName = receiveParam[2];   // '2.氏名
            // 漢字名
            pSex = receiveParam[4];    // '4.性別
            pSng = receiveParam[5];    // '5.生年月日
            pAge = receiveParam[6];    // '6.年齢
            pHeight = receiveParam[7]; // '7.身長
            pWeight = receiveParam[8]; // '8.体重

            Log.d(TAG, "肺活量計（SP-350COPD） pNO , pName , pSex , pSng , pAge , pHeight , pWeight" +
                    pNO + ":::" + pName + ":::" + pSex +
                    ":::" + pSng + ":::" + pAge + ":::" +
                    pHeight + ":::" + pWeight);

            // 出力項目のクリア
            clearReceiveValues();

            // ASK  VB => Chr(6)
            char strSendAck_tmp = 0x06;   // ACK (受信OK)
            strSendAck = String.valueOf(strSendAck_tmp);

            // CAN   VB => Chr(&h18)
            char strSendCan_tmp = 0x18;
            strSendCan = String.valueOf(strSendCan_tmp); // CAN (取消)

            // ********* マスターモードで仮想シリアルポート接続 *********
            btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                // ENQ 待ち  ENQ コマンド:「(今からデータを送っても)大丈夫？」のような、問い合わせ用のコード
                // vb ソース   Recv(byStatus, intRecvSize)
                btRet = connection.receiveDeviceValue(status, resultSize);

                // キャンセル
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    return;
                }
                Log.d(TAG, "肺活量 SP-350COPD = " + btRet);

                // contains での　ロジック
                String Tmp_i = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "肺活量 SP-350COPD = Tmp_i ::: " + Tmp_i);

                // === 比較用 ENQ ===
                char Chr_5 = 0x05;
                String Chr_5_str = String.valueOf(Chr_5);

                if(Tmp_i.contains(Chr_5_str)) {
                    i = 1;
                    Log.d(TAG, "if 文内 ::: i:::" + "値:::" + i);
                } else {
                    i = 0;
                    Log.d(TAG, "if 文内 else 比較文字列無し ::: i:::" + "値:::" + i);
                }
                // === 比較用 ENQ ===

                if (i < 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "肺活量計（SP-350COPD） = i 受信キャンセル" + i);
                    return;
                }

                // ASK 送信 ASKコマンド:0x06 「OK」「わかりました」のような、肯定する応答(返事)を示すコード
                // === コマンド　送信 ===  0x06 strSendAck
                byBuff = strSendAck.getBytes(AppCharset.SHIFT_JIS);
                connection.send(byBuff, byBuff.length);

                // 属性問い合わせ待ち
                // vb ソース   Recv(byStatus, intRecvSize)
                btRet = connection.receiveDeviceValue(status, resultSize);

                if (btRet < 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    return;
                }

                String Tmp_i_02 = ByteUtil.bytesToString(status, 0, resultSize.get());

                // === 比較用 STX  (ﾃｷｽﾄ開始) ===
                char Chr_2 = 0x02;
                String Chr_2_str = String.valueOf(Chr_2);

                if(Tmp_i_02.contains(Chr_2_str)) {
                    i = 1;
                    Log.d(TAG, "if 文内 STX  (ﾃｷｽﾄ開始) ::: i:::" + "値:::" + i);
                } else {
                    i = 0;
                    Log.d(TAG, "if 文内 STX  (ﾃｷｽﾄ開始) else 比較文字列無し ::: i:::" + "値:::" + i);
                }
                // === 比較用 STX END ===

                if (i < 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "肺活量計（SP-350COPD） = [Recv] 受信キャンセル (STX無し)" + i);
                    return;
                }

                // 属性データ作成
                //=== STX
                char char_Tmp_01 = 0x02;
                strCommandG = String.valueOf(char_Tmp_01);

                strCommandG += "0";

                //=== 患者ID（13）
                strCommandG += ByteUtil.bytesToString(status, 8 - 1, 13);
                //strCommandG += ByteUtil.bytesToString(status, 7 - 1, 13).trim();

                //=== 検査番号
                // Val pNO
                String Val_pNO = pNO.replaceAll("[^\\d]", "").trim();
                Log.d(TAG, "肺活量計（SP-350COPD） = 変数出力::Val_   pNO " + Val_pNO);

                String format_L_pNO = String.format("%9s", Val_pNO);
                Log.d(TAG, "肺活量計（SP-350COPD） = 変数出力::format_L_pNO " + format_L_pNO);
                strCommandG += format_L_pNO;

                //=== 氏名（30） 右側に  ( 30 - 氏名 ) パディング
                String format_R_pName = String.format("%-30s", pName);
                Log.d(TAG, "肺活量計（SP-350COPD） = 変数出力::format_R_pName " + format_R_pName);
                strCommandG += format_R_pName;

                //=== 生年月日
                strCommandG += pSng;

                //=== 年齢（3）
                // Val pAge
                String Val_pAge = pAge.replaceAll("[^\\d]", "").trim();
                Log.d(TAG, "肺活量計（SP-350COPD） = 変数出力::Val_pAge " + Val_pAge);
                String format_L_pAge = String.format("%3s", Val_pAge);
                Log.d(TAG, "肺活量計（SP-350COPD） = 変数出力::format_L_pAge " + format_L_pAge);

                strCommandG += format_L_pAge;
                Log.d(TAG, "肺活量計（SP-350COPD） = 変数出力::strCommandG " + strCommandG);

                //=== 性別
                switch (pSex) {
                    case "1":
                        strCommandG += "M";
                        Log.d(TAG, "肺活量計（SP-350COPD）case 1 = 変数出力::pSex " + pSex);
                        break;
                    case "2":
                        strCommandG += "F";
                        Log.d(TAG, "肺活量計（SP-350COPD）case 2 = 変数出力::pSex " + pSex);
                        break;
                    default:
                        strCommandG += "N";
                        Log.d(TAG, "肺活量計（SP-350COPD）case default = 変数出力::pSex " + pSex);
                        break;
                } // === END swich

                //=== 身長
                if (pHeight.trim().length() == 0) {
                    strCommandG += "000.0";
                } else {
                    strCommandG += pHeight.format("%5s", pHeight).replace(" ", "0");
                } // === END if
                Log.d(TAG, "肺活量計（SP-350COPD）変数出力::pHeight " + pHeight);

                //=== 体重
                if (pWeight.trim().length() == 0) {
                    strCommandG += "000.0";
                } else {
                    strCommandG += pWeight.format("%5s", pWeight).replace(" ", "0");
                } // === END if
                Log.d(TAG, "肺活量計（SP-350COPD）変数出力::pWeight " + pWeight);

                //=== 依頼科（30）
                strCommandG += String.format("%-30s", " ");
                Log.d(TAG, "肺活量計（SP-350COPD）変数出力::strCommandG " + strCommandG);

                //=== 医師名（30）
                strCommandG += String.format("%-30s", " ");
                Log.d(TAG, "肺活量計（SP-350COPD）変数出力::strCommandG " + strCommandG);

                //=== ETX  Chr(3) => ETX (ﾃｷｽﾄ終了) 0x03 電文(データ部または電文全体)の終了位置を示すコード
                char char_ETX = 0x03;
                String str_Ext = String.valueOf(char_ETX);
                strCommandG += str_Ext;

                byBuff = strCommandG.getBytes(AppCharset.SHIFT_JIS);
                // コマンド送信
                connection.send(byBuff, byBuff.length);

                //=== ASK　待ち
                // vb ソース   Recv(byStatus, intRecvSize)
                btRet = connection.receiveDeviceValue(status, resultSize);
                if (btRet < 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "肺活量計（SP-350COPD） = [Recv] 受信キャンセル (STX無し)" + i);
                    return;
                }

                String Tmp_ii = ByteUtil.bytesToString(status, 0, resultSize.get());
                //=== ASK contains Start
                char Chr_6 = 0x06;
                String Chr_6_str = String.valueOf(Chr_6);

                if(Tmp_ii.contains(Chr_6_str)) {
                    i = 1;
                    Log.d(TAG, "if 文内 ASK ::: i:::" + "値:::" + i);
                } else {
                    i = 0;
                    Log.d(TAG, "if 文内 ASK else 比較文字列無し ::: i:::" + "値:::" + i);
                }
                //=== ASK contains END

                if (i < 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "肺活量計（SP-350COPD） = [Recv] 受信キャンセル (ASK)");
                    return;
                }

                //=== ENQ 待ち
                btRet = connection.receiveDeviceValue(status, resultSize);
                if (btRet < 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "肺活量計（SP-350COPD） = [Recv] 受信キャンセル (ENQ 待ち)" + i);
                    return;
                }

                //=== ENQ contains start
                String Tmp_iii = ByteUtil.bytesToString(status, 0, resultSize.get());
                char Chr_5_02 = 0x05;
                String Chr_5_02_str = String.valueOf(Chr_5_02);

                if(Tmp_iii.contains(Chr_5_02_str)) {
                    i = 1;
                    Log.d(TAG, "if 文内 ENQ ::: i:::" + "値:::" + i);
                } else {
                    i = 0;
                    Log.d(TAG, "if 文内 ENQ ::: i:::" + "値:::" + i);
                }

                if (i < 0) {
                    // キャンセル ENQ なし
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "肺活量計（SP-350COPD） = [Recv] 受信キャンセル (ENQ なし)" + i);
                    return;
                }

                //=== ASK送信
                // === コマンド　送信 ===  0x06
                byBuff = strSendAck.getBytes(AppCharset.SHIFT_JIS);
                connection.send(byBuff, byBuff.length);

                bCancelFlag = false;

                int test_count = 0;

                boolean Case_Flg = false;

                boolean loop_Flg = true; //　ループ用　フラグ
                while (loop_Flg) {

                    //=== 値取得　 btRet = connection.receiveDeviceValue(status, resultSize);　コマンドは最後の RSR , 06 04 06 を返していない
                    //=== これ => コマンドはちゃんと　返ってるが値がちゃんととれない、connection.receive(status, status.length, resultSize);

                    //    btRet = connection.receive(status, status.length, resultSize);
                    btRet = connection.receiveDeviceValue(status, resultSize);
                    //  connection.receiveDeviceValue(status, resultSize);
                    //  connection.receive(status, status.length, resultSize);
                    Log.d(TAG, "肺活量計（SP-350COPD） = while 内 ::: btRet ::: test_count " + btRet + ":::" + test_count);

                    if (resultSize.get() <= 0) {
                        bCancelFlag = true;
                    }
                    /*
                    else {
                        bCancelFlag = false;
                    }

                     */

                    Log.d(TAG, "肺活量計（SP-350COPD） = while 内 :::  配列::: status[0]" + status[0]);

                    switch (status[0]) {
                        case 1:
                            // SOH 先頭のヘッダーレコード
                            if (!bCancelFlag) {
                                // ASK 送信 strSendAck
                                byBuff = strSendAck.getBytes(AppCharset.SHIFT_JIS);
                                connection.send(byBuff, byBuff.length);
                            } else {
                                // CAN送信 strSendCan
                                byBuff = strSendCan.getBytes(AppCharset.SHIFT_JIS);
                                connection.send(byBuff, byBuff.length);
                            }

                            break;
                        case 2:
                            // STX 通常レコード
                            Log.d(TAG, "case 2 : BtSP350COPD_sub1 引数 => resultSize.get()" + resultSize.get());

                            // ********************  BtSP350COPD_sub1 function ********************
                            BtSP350COPD_sub1(status, resultSize.get());

                            // SOH 先頭のヘッダーレコード
                            if (!bCancelFlag) {
                                // ASK 送信
                                byBuff = strSendAck.getBytes(AppCharset.SHIFT_JIS);
                                connection.send(byBuff, byBuff.length);
                            } else {
                                // CAN送信
                                byBuff = strSendCan.getBytes(AppCharset.SHIFT_JIS);
                                connection.send(byBuff, byBuff.length);
                            }

                            break;
                        case 0x18 : // 24: // Chr(&H18)
                            // CAN 分析器　での　キャンセル
                            // CAN送信
                            byBuff = strSendAck.getBytes(AppCharset.SHIFT_JIS);
                            connection.send(byBuff, byBuff.length);
                            bCancelFlag = true;

                            return;
                        case 4: // Chr(4)
                            // EOT 正常終了
                            // ACK  送信
                            byBuff = strSendAck.getBytes(AppCharset.SHIFT_JIS);
                            connection.send(byBuff, byBuff.length);

                            return;
                        default:
                            //　無処理
                            break;
                    } // END switch

                    //===============> break でここで抜けないと、後の for がエラーになる。
                    // loop_Flg = true; // while 用　ループフラグ true の場合はループを回している。
                    Log.d(TAG, "肺活量計（SP-350COPD while ループ内::: while loop_Flg :::" + loop_Flg);

                    test_count++;
                    Log.d(TAG, "肺活量計（SP-350COPD while ループ内::: test_count ::: 値" + test_count);


                } // END while

                Log.d(TAG, "肺活量計（SP-350COPD while ＊＊＊＊＊＊＊＊＊＊＊＊＊＊＊＊　ループ　抜け ＊＊＊＊＊＊＊＊＊＊＊＊＊＊＊＊＊" +
                        " bCancelFlag :::" + bCancelFlag);

                if (!bCancelFlag) {

                    for (int k = 1; k < 8; k++) {
                        receiveData[k] = receiveData[k].trim();
                        // && StringUtil.isNumeric(receiveData[k]) == false

                        Log.d(TAG, "肺活量計（SP-350COPD forループ内::: receiveData[k]" + receiveData[k]);

                        if (receiveData[k].length() > 0 && StringUtil.isNumeric(receiveData[k]) == false) {
                            // ブランク　数字　以外はエラー
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "肺活量計（SP-350COPD） = [Recv] 異常終了");
                        }


                    } // ======= END for

                    Log.d(TAG, "loop_Flg while 抜け後");

                    // 肺活量（L）
                    if (receiveData[1].length() != 0) {
                        // 10進数 で 0.00 の書式
                        //   receiveData[1] = String.format("%0.00d", receiveData[1]);
                        Log.d(TAG, "receiveData[1] 取得前");
                        receiveData[1] = receiveData[1];
                        Log.d(TAG, "値取得 length() != 0 receiveData[1] " + receiveData[1]);
                    }
                    // 一秒量（L）
                    if (receiveData[3].length() != 0) {
                        // 10進数 で　0.00
                        //  receiveData[3] = String.format("%0.00d", receiveData[3]);
                        receiveData[3] = receiveData[3];
                        Log.d(TAG, "値取得 length() != 0 receiveData[3] " + receiveData[3]);
                    }
                    // 予測肺活量（L）
                    if (receiveData[5].length() != 0) {
                        // 10進数 で　0.00
                        //    receiveData[5] = String.format("%0.00d", receiveData[5]);
                        receiveData[5] = receiveData[5];
                        Log.d(TAG, "値取得 length() != 0 receiveData[5] " + receiveData[5]);
                    }
                    // 努力肺活量（L）
                    if (receiveData[6].length() != 0) {
                        // 10進数 で　0.00
                        //     receiveData[6] = String.format("%0.00d", receiveData[6]);
                        receiveData[6] = receiveData[6];
                        Log.d(TAG, "値取得 length() != 0 receiveData[6] " + receiveData[6]);
                    }
                    // 予測一秒量（L）
                    if (receiveData[7].length() != 0) {
                        // 10進数 で　0.00
                        //    receiveData[7] = String.format("%0.00d", receiveData[7]);
                        receiveData[7] = receiveData[7];
                        Log.d(TAG, "値取得 length() != 0 receiveData[7] " + receiveData[7]);
                    }

                    //====== １秒率 ======
                    if (receiveData[4].length() != 0) {
                        // String Temp_receiveData_4 = receiveData[4].substring(0, receiveData[4].length() - 1);
                        receiveData[4] =  receiveData[4];
                        Log.d(TAG, "値取得 length() != 0 receiveData[4] " + receiveData[4]);
                    } else {
                        receiveData[4] = "0";
                    } // ====== END IF

                    if (receiveData[8].length() != 0) {
                        receiveData[8] = receiveData[8];
                        Log.d(TAG, "値取得 length() != 0 receiveData[8] " + receiveData[8]);
                    } else {
                        receiveData[8] = "0";
                    } // ====== END IF

                } else {

                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "肺活量計（SP-350COPD） = [Recv] キャンセル");
                    return;
                }

                //  '仮想シリアルポート切断()
                connection.close();
                // 正常終了
                //  setReceiveRetWithBtRet(btRet);

            } else {

                // キャンセル
                receiveRet = StatusConstants.RET_ERR_CANCEL;
                Log.d(TAG, "肺活量計（SP-350COPD） = [Recv] キャンセル");
                return;

            }

            // 正常終了
            setReceiveRetWithBtRet(btRet);

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;

        } finally {

            // 正常終了
            setReceiveRetWithBtRet(btRet);

            //  '仮想シリアルポート切断()
            connection.close();

        }

    }

    private void btFX3(BluetoothSppConnection connection, String address) {
    }

    private void btSP750COPD(BluetoothSppConnection connection, String address) {
    }

    private void btHI701(BluetoothSppConnection connection, String address) {
    }

    private void btSYSTEM7(BluetoothSppConnection connection, String address) {
    }

    private void btNCT10(BluetoothSppConnection connection, String address) {
    }

    private void btXpertPlus(BluetoothSppConnection connection, String address) {
    }

    /**
     * 眼圧計 トプコン CT-70 (CT-1)
     */
    private void btCT70(BluetoothSppConnection connection, String address) {
        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        int ii = 0;
        String strResult;

        int intIndex;
        String strData;
        String strRight;
        String strLeft;

        try {
            // 出力項目のクリア
            clearReceiveValues();

            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);
            //**************************************************************************************
            //**************************************************************************************
            if (btRet == StatusConstants.BT_SUCCESS) {
                btRet =  connection.receiveDeviceValue(status,resultSize);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "眼圧計 (CT-1): キャンセル = " + receiveRet);
                    return;
                }

                // 値取得
                strResult = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "眼圧計 (CT-1)取得データ：：： strResult = " + strResult);

                //=========================================================
                //========================= 体組成計 =======================
                //=========================================================
                if (strResult.contains("@")) {
                    // 右 取得
                    intIndex = strResult.indexOf("R ");
                    if (intIndex > 0) {
                        //1回目
                        strData = strResult.substring(intIndex + 2, intIndex + 2 + 4).trim();
                        Log.d(TAG, "眼圧計 (CT-1)取得データ：：： 右眼(1) = " + strData);
                        if (!strData.chars().allMatch( Character::isDigit )) {
                            //不安定
                            strRight = "99";
                        }else{
                            strRight = strData;
                        }
                        Log.d(TAG, "眼圧計 (CT-1)取得データ：：： 右眼(1) = " + strRight);
                        //2回目
                        strData = strResult.substring(intIndex + 6, intIndex + 6 + 4).trim();
                        Log.d(TAG, "眼圧計 (CT-1)取得データ：：： 右眼(2) = " + strData);
                        //if (!strData.contains("(")) {
                        if (strData.chars().allMatch( Character::isDigit )) {
                            if (Integer.parseInt(strRight) >  Integer.parseInt(strData)) {
                                strRight = strData;
                            }
                        }
                        Log.d(TAG, "眼圧計 (CT-1)取得データ：：： 右眼(2) = " + strRight);
                        //3回目
                        strData = strResult.substring(intIndex + 10, intIndex + 10 + 4).trim();
                        Log.d(TAG, "眼圧計 (CT-1)取得データ：：： 右眼(3) = " + strData);
                        if (strData.chars().allMatch( Character::isDigit )) {
                            if (Integer.parseInt(strRight) >  Integer.parseInt(strData)) {
                                strRight = strData;
                            }
                        }
                        Log.d(TAG, "眼圧計 (CT-1)取得データ：：： 右眼(3) = " + strRight);
                    }else{
                        strRight = "";
                    }
                    Log.d(TAG, "眼圧計 (CT-1)取得データ：：： 右眼 = " + strRight);

                    // 左 取得
                    intIndex = strResult.indexOf("L ");
                    if (intIndex > 0) {
                        //1回目
                        strData = strResult.substring(intIndex + 2, intIndex + 2 + 4).trim();
                        if (!strData.chars().allMatch( Character::isDigit )) {
                            //不安定
                            strLeft = "99";
                        }else{
                            strLeft = strData;
                        }
                        //2回目
                        strData = strResult.substring(intIndex + 6, intIndex + 6 + 4).trim();
                        if (strData.chars().allMatch( Character::isDigit )) {
                            if (Integer.parseInt(strLeft) >  Integer.parseInt(strData)) {
                                strLeft = strData;
                            }
                        }
                        //3回目
                        strData = strResult.substring(intIndex + 10, intIndex + 10 + 4).trim();
                        if (strData.chars().allMatch( Character::isDigit )) {
                            if (Integer.parseInt(strLeft) >  Integer.parseInt(strData)) {
                                strLeft = strData;
                            }
                        }
                    }else{
                        strLeft = "";
                    }
                    Log.d(TAG, "眼圧計 (CT-1)取得データ：：： 左眼 = " + strLeft);
                    // 右眼
                    if (strRight != "99") {
                        receiveData[1] = strRight;
                    }else{
                        receiveData[1] = "";
                    }
                    // 左眼
                    if (strLeft != "99") {
                        receiveData[2] = strLeft;
                    }else{
                        receiveData[2] = "";
                    }
                    // 正常終了
                    setReceiveRetWithBtRet(btRet);
                } else {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "眼圧計 (CT-1) 値取得失敗(2)");
                    return;
                }
            } else {
                // ========= エラー
                Log.d(TAG, "眼圧計 (CT-1) 接続失敗");
                receiveRet = StatusConstants.RET_ERR_MEASURE;
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }
    }

    private void btNCT200(BluetoothSppConnection connection, String address) {
    }

    /**
     * 眼圧計 TX-20P
     */
    private void btTX20P(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        byte[] byBuff = new byte[2]; // 送信バッファ

        String strMid_R; // strData の Mid関数での切り出し　判定用　（Java substring）
        String strMid_L;
        String MidStr_Temp;
        String MidStr_Sub_R;
        String MidStr_Sub_L;
        String strSendAck;

        //   byte[] bytesSendAck = new byte[2]; // Ask 送信用
        int i = 0;
        String strData;
        String strTemp;
        int intL = 99;
        int intR = 99;
        String strR = "";
        String strL = "";
        int iRcnt, iLcnt;
        int btRet = 0;

        try {
            // 出力項目のクリア
            clearReceiveValues();

            // ASK
            /*
            byBuff[0] = 0x06 ; // 文字コード 6 ACK (受信OK)
            byBuff[1] = 0x0d; // 改行コード 13
             */

            // コマンド chr(6)
            char c_cmd_buff_01 = 0x06;
            String cmd_str_01 = String.valueOf(c_cmd_buff_01);
            // コマンド vbLf => chr(13)
            char c_cmd_str_02 = 0x0d;
            String cmd_str_02 = String.valueOf(c_cmd_str_02);

            strSendAck = cmd_str_01 + cmd_str_02;
            Log.d(TAG, "TX-20P + strSendAck:::値:::" + strSendAck);


            byte buffer[] = new byte[2];
            buffer[0] = 0x06; // 6
            buffer[1] = 0x0d; // 13

            int buffer_length = buffer.length;

            // マスターモードで仮想シリアルポート接続
            btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                // ENQ 待ち
                btRet = connection.receiveDeviceValue(status, resultSize);

                // キャンセル
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    return;
                }
                Log.d(TAG, "眼圧計 TX-20P = " + btRet);


                String Tmp_i = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "眼圧計 TX-20P Tmp_i = " + Tmp_i);

                // === 比較用 ENQ ===
                char Chr_5 = 0x05;
                String Chr_5_str = String.valueOf(Chr_5);

                if(Tmp_i.contains(Chr_5_str)) {
                    i = 1;
                    Log.d(TAG, "if 文内 ::: i:::" + "値:::" + i);
                } else {
                    i = 0;
                    Log.d(TAG, "if 文内 else 比較文字列無し ::: i:::" + "値:::" + i);
                }
                // === 比較用 ENQ ===

                if (i == 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "眼圧計 TX-20P 「キャンセル」 = " + i);
                    return;
                }

                // コマンド送信　ASK 送信
                // === コマンド　送信 ===  0x06 strSendAck
                byBuff = strSendAck.getBytes(AppCharset.SHIFT_JIS);
                connection.send(byBuff, byBuff.length);

                // OK    connection.send(buffer, buffer_length);
                Log.d(TAG, "buffer の値 = " + buffer + ":::buffer のlength:::" + buffer_length);

                // ループ開始
                while (true) {
                    i = 1;

                    // intRet = Recv(byStatus, intRecvSize) VB ソース
                    btRet = connection.receiveDeviceValue(status, resultSize);

                    // キャンセル
                    if (resultSize.get() <= 0) {
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "btTX20P 眼圧計】［receiveRet］受信キャンセル" + receiveRet);
                        return;
                    }

                    strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                    strData = strData.replace("\n", "");
                    //strData = strData.replace("\n", " ");
                    Log.d(TAG, "btTX20P 眼圧計】取得データ：：： strData = " + strData);

                    // 標準値を採用するパターン
                    // strData ="   RIGHT     LEFT    C [ 15.8      13.7 ] C [ 15.8      13.7 ] C [ 15.8      13.7 ] C"
                    i = strData.indexOf("  RIGHT     LEFT    "); //  index.of で　0 を vb では　1 を取得する

                    if (i > 0) {

                        iRcnt = 22;
                        iLcnt = 39;

                        for (int j = 0; j < 10; j++) {

                            MidStr_Sub_R = strData.substring(i + iRcnt, i + iRcnt + 1);
                            MidStr_Sub_L = strData.substring(i + iLcnt, i + iLcnt + 1);

                            Log.d(TAG, "btTX20P 眼圧計】 for 内 MidStr_Sub_R = " + MidStr_Sub_R);
                            Log.d(TAG, "btTX20P 眼圧計】 for 内 MidStr_Sub_L = " + MidStr_Sub_L);

                            // substring で データ MidStr_Sub_R => [  , MidStr_Sub_L => ] があった場合
                            if (MidStr_Sub_R.equals("[") && MidStr_Sub_L.equals("]")) {

                                strTemp = strData.substring(i + iRcnt + 2, i + iRcnt + 2 + 4);

                                if (!(strTemp.equals("    "))) {

                                    // 四捨五入
                                    float f_tmp = (float) Math.round(Double.parseDouble(strTemp));
                                    // 値格納
                                    strR = String.format("%,.0f", f_tmp);
                                    Log.d(TAG, "for 内 strR = " + strR);

                                } else {
                                    strR = "";
                                }

                                strTemp = strData.substring(i + iRcnt + 12, i + iRcnt + 12 + 4);
                                if (!(strTemp.equals(""))) {

                                    // 四捨五入
                                    float f_tmp = (float) Math.round(Double.parseDouble(strTemp));
                                    // 値格納
                                    strL = String.format("%,.0f", f_tmp);
                                    Log.d(TAG, "btTX20P 眼圧計】 strL = " + strL);

                                } else {
                                    strL = "";
                                }

                                break;

                            } else {

                                iRcnt += 21;
                                iLcnt += 21;

                            }  // ====== End if
                        } // ========= End for

                        // ASK 送信
                        byBuff = strSendAck.getBytes(AppCharset.SHIFT_JIS);
                        connection.send(byBuff, byBuff.length);
                        // OK    connection.send(buffer, buffer_length);

                    } // ====== End if

                    // === 比較用 EOT ===
                    char i_eot = 0x04;
                    String i_eot_str = String.valueOf(i_eot);
                    Log.d(TAG, "btTX20P 眼圧計】 while 抜け前 = " + i_eot_str);

                    if(strData.contains(i_eot_str)) {
                        i = 1;
                        Log.d(TAG, "btTX20P 眼圧計】 while 抜け前 = ::: i:::" + "値:::" + i);
                    } else {
                        i = 0;
                        Log.d(TAG, "btTX20P 眼圧計】 while 抜け前 = 比較文字列無し ::: i:::" + "値:::" + i);
                    }
                    // === 比較用 ENQ ===

                    if (i > 0) {
                        // ASK 送信
                        byBuff = strSendAck.getBytes(AppCharset.SHIFT_JIS);
                        connection.send(byBuff, byBuff.length);

                        break;
                    }

                }   // ============ End while ============

                Log.d(TAG, "btTX20P 眼圧計】 ********* while ループ抜け ********* = strR + strL " + strR + strL);

                //===========================================================
                //======================== 眼圧　値取得 =======================
                //===========================================================
                if (!(strR.equals(""))) {
                    receiveData[1] = strR;
                } else {
                    receiveData[1] = "0";
                }

                if (!(strL.equals(""))) {
                    receiveData[2] = strL;
                } else {
                    receiveData[2] = "0";
                }
                // 仮想シリアルポート切断
                setReceiveRetWithBtRet(btRet);
            } else {
                receiveRet = StatusConstants.RET_ERR_MEASURE;
                Log.d(TAG, "btTX20P 眼圧計】 else receiveRet:::" + receiveRet);
                return;

            }  // =========== END if ======================

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
            Log.d(TAG, "btTX20P 眼圧計】 Exception" + receiveRet);
        }

    }

    /***
     *   骨密度 AOS 100 ? デバイス:id26　=> ほたる（骨密度）で呼ばれる関数
     */
    private void btAOS100NW_S(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        String strCommandG;    // 送信パラメータ用
        String strData = null; //

        String pNum = null;    // 1.健診者番号
        String pName = null;   // 2.氏名
        String pSex = null;    // 3.性別
        String pSng = null;    // 4.生年月日

        String str_CrLf = null; // vbCrLf 用　文字列

        // ======= パラメーター vb ReceiveParam(1), (2), (3), (4) 取得
        // (1) 1.健診者番号 (2) 2.氏名  (3) 3.性別  (4) 4.生年月日
        try {

            pNum = receiveParam[1];  // 1.健診者番号
            pName = receiveParam[2]; // 2.氏名
            pSex = receiveParam[3];  // 3.性別
            pSng = receiveParam[4];  // 4.生年月日

            Log.d(TAG, "骨密度 AOS 100 ,pName , pName , pSex , pSng  = " + pNum + pName + pSex + pSng);

            // 出力項目のクリア
            clearReceiveValues();

            // 送信パラメーター（STX） Chr(2)
            char char_c_02 = 0x02;
            strCommandG = String.valueOf(char_c_02);

            // CRLF 文字列作成
            char C_Cr = 0x0d; // Cr
            char C_Lf = 0x0a; // Lf
            str_CrLf = String.valueOf(C_Cr) + String.valueOf(C_Lf);

            /*
            byte[] CRLF = "\r\n".getBytes("SJIS");
            String str_CRLF = new String(CRLF);
             */

            strCommandG += pNum.trim() + str_CrLf;

            // 氏名（漢字）
            strCommandG += String.format("%-18s", pName) + str_CrLf;
            Log.d(TAG, "骨密度 AOS 100 strCommandG,  pName = " + strCommandG);

            // 性別
            switch (pSex) {
                case "1":
                    strCommandG += "男性" + str_CrLf;
                    break;
                default:
                    strCommandG += "女性" + str_CrLf;
                    break;
            }

            // 生年月日（YYYY.MM.DD）

            strCommandG += pSng.substring(0, 4) + "/" + pSng.substring(4, 4 + 2) + "/" + pSng.substring(6, 6 + 2) +
                    str_CrLf;

            // 部位
            strCommandG += "右踵骨" + str_CrLf;

            Log.d(TAG, "骨密度 AOS 100 右踵骨 後::: ,  strCommandG = " + strCommandG);

            // [ETX]
            char chr_03 = 0x03;
            strCommandG += String.valueOf(chr_03);

            // ====== マスターモードで仮想シリアルポート接続
            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                Log.d(TAG, "骨密度 AOS 100 パラメーター送信前::: ,  strCommandG = " + strCommandG);

                // パラメーター送信
                byte[] byBuff = strCommandG.getBytes(AppCharset.SHIFT_JIS);
                //  byte[] byBuff = strCommandG.getBytes();
                connection.send(byBuff, byBuff.length);

                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                Log.d(TAG, "骨密度 AOS 100 = " + btRet);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: キャンセル = " + receiveRet);
                    return;
                }

                Log.d(TAG, "骨密度 AOS 100: ReceiveParam 値取得：：：" + pNum + "," + pName +
                        "," + pSex + "," + pSng);

                Log.d(TAG, "骨密度 AOS 100: byBuff Arrays.toString, new Strin ★★★★★★★★★★" +
                        Arrays.toString(byBuff) + "," + new String(byBuff));

                // ========= STX チェック ( ﾃｷｽﾄ開始 ) =========
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "骨密度 AOS 100】取得データ：：： strData = " + strData);

                // strData の　先頭の文字が VB の Chr(2) => Java 0x02 => 16進数
                String Left_strData_01_s = strData.substring(0, 1);
                byte[] Left_strData_01_b = Left_strData_01_s.getBytes();
                Log.d(TAG, "骨密度 AOS 100】取得データ：：： Left_strData_01_b = " + Left_strData_01_b);

                // 比較用文字
                byte[] hikaku_b = new byte[1];
                hikaku_b[0] = 0x02;

                // 先頭の文字が、 STX (ﾃｷｽﾄ開始) 0x02 じゃなかったら　エラー処理
                if (!(Arrays.equals(Left_strData_01_b, hikaku_b))) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受付付加");
                    return;
                }

                // ========= 受付番号 チェック =========
                // strData の　２文字目から　切り出し
                // String MidStr = strData.substring(2);
                String MidStr = strData.substring(1);

                // VB の Val 変換
                String Mid_strData_01 = MidStr.replaceAll("[^\\d]", "");
                String Temp_pNum = pNum.replaceAll("[^\\d]]", "");

                Log.d(TAG, "骨密度 AOS 100: " + "Mid_strData_01:::値:::" + Mid_strData_01 + "....Temp_pNum:::値:::" + Temp_pNum);

                if (!(Mid_strData_01.equals(Temp_pNum))) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;

                    Log.d(TAG, "骨密度 AOS 100: [Recv]" + "Mid_strData_01:::" + Mid_strData_01 + ":::Temp_pNum:::" + Temp_pNum);

                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信番号違い キャンセル" +
                            Arrays.toString(byBuff) + "," + new String(byBuff));

                    return;
                }

                // 該当箇所まで　スキップ
                for (int ii = 1; ii <= 14; ii++) {
                    btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                    Log.d(TAG, "骨密度 AOS 100 for文内 vbCrLf 付き  = " + btRet);

                    if (resultSize.get() <= 0) {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "骨密度 AOS 100: キャンセル for文 内 if  = " + receiveRet);
                        return;
                    }

                } //========== END for

                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル = " + receiveRet);
                    return;
                }

                //============= 値取得 receiveData[1]
                //strData =  ByteUtil.bytesToString(status, 0, resultSize.get()).trim();
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                receiveData[1] = strData; // 骨密度

                Log.d(TAG, "骨密度 AOS 100: = receiveData[1] " + receiveData[1]);

                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル receiveData[1] 骨密度　取得  = " + receiveRet);
                    return;
                }

                // 同年比較
                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル = " + receiveRet);
                    return;
                }

                //============= 値取得 receiveData[2]
                //strData =  ByteUtil.bytesToString(status, 0, resultSize.get()).trim();
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                receiveData[2] = strData;

                Log.d(TAG, "骨密度 AOS 100: = receiveData[2] " + receiveData[2]);

                // 該当箇所までスキップ
                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル = " + receiveRet);
                    return;
                }

                // 最大骨塩量
                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル = " + receiveRet);
                    return;
                }

                //============= 値取得 receiveData[3]
                //strData =  ByteUtil.bytesToString(status, 0, resultSize.get()).trim();
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                receiveData[3] = strData;

                Log.d(TAG, "骨密度 AOS 100: = receiveData[3] " + receiveData[3]);

                // ETX チェック
                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル [ETX チェック] = " + receiveRet);
                    return;
                }

                //strData =  ByteUtil.bytesToString(status, 0, resultSize.get()).trim();
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());

                Log.d(TAG, "骨密度 AOS 100: = strData " + strData);

                // 0x03
                String Chr_3 = strData.substring(0, 1);
                byte[] bx_03 = new byte[1];
                bx_03 = Chr_3.getBytes();

                // 比較用
                byte[] tx_03 = new byte[1];
                tx_03[0] = 0x03;

                if (!(Arrays.equals(bx_03, tx_03))) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受付不可 if Left(strData, 1) <> Chr(3) Then = ");
                    return;
                }

                // 検査値チェック
                for (int i = 1; i <= 3; i++) {

                    if (StringUtil.isNumeric(receiveData[i]) == false) {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "骨密度 AOS 100: [Recv] 異常終了 = " + receiveData[i]);
                        return;
                    }

                } //========== END for

                // 正常終了
                setReceiveRetWithBtRet(btRet);

            }

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;

        } finally {
            //  '仮想シリアルポート切断()
            connection.close();
        }

    }


    /***
     *   骨密度 AOS 100
     */
    private void btAOS100NW(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        String strCommandG;    // 送信パラメータ用
        String strData = null; //

        String pNum = null;    // 1.健診者番号
        String pName = null;   // 2.氏名
        String pSex = null;    // 3.性別
        String pSng = null;    // 4.生年月日

        String test_age = null; // テスト　年齢

        String str_CrLf = null; // vbCrLf 用　文字列

        // ======= パラメーター vb ReceiveParam(1), (2), (3), (4) 取得
        // (1) 1.健診者番号 (2) 2.氏名  (3) 3.性別  (4) 4.生年月日
        try {

            pNum = receiveParam[1];  // 1.健診者番号
            pName = receiveParam[2]; // 2.氏名
            pSex = receiveParam[3];  // 3.性別
            pSng = receiveParam[4];  // 4.生年月日
            //     test_age = receiveParam[5]; // 年齢

            Log.d(TAG, "骨密度 AOS 100 ,pName , pName , pSex , pSng  = " + "pNum:::" +
                    pNum + ":::pName:::" + pName + ":::pSex:::" + pSex + ":::pSng:::" + pSng + "test_age" + test_age);

            // 出力項目のクリア
            clearReceiveValues();

            // 送信パラメーター（STX） Chr(2)
            char char_c_02 = 0x02;
            strCommandG = String.valueOf(char_c_02);

            // CRLF 文字列作成
            char C_Cr = 0x0d; // Cr
            char C_Lf = 0x0a; // Lf
            str_CrLf = String.valueOf(C_Cr) + String.valueOf(C_Lf);

            /*
            byte[] CRLF = "\r\n".getBytes("SJIS");
            String str_CRLF = new String(CRLF);
             */

            strCommandG += pNum.trim() + str_CrLf;

            // 氏名（漢字）
            //strCommandG += String.format("%-18s", pName) + str_CrLf;
            strCommandG += StringUtil.padSpaceRight(pName, 18) + str_CrLf;
            Log.d(TAG, "骨密度 AOS 100 strCommandG,  pName = " + strCommandG);

            // 性別
            switch (pSex) {
                case "1":
                    strCommandG += "男性" + str_CrLf;
                    break;
                default:
                    strCommandG += "女性" + str_CrLf;
                    break;
            }

            // 生年月日（YYYY.MM.DD）

            strCommandG += pSng.substring(0, 4) + "/" + pSng.substring(4, 4 + 2) + "/" + pSng.substring(6, 6 + 2) +
                    str_CrLf;

            // 部位
            strCommandG += "右踵骨" + str_CrLf;

            Log.d(TAG, "骨密度 AOS 100 右踵骨 後::: ,  strCommandG = " + strCommandG);

            // [ETX]
            char chr_03 = 0x03;
            strCommandG += String.valueOf(chr_03);

            // ====== マスターモードで仮想シリアルポート接続
            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                Log.d(TAG, "骨密度 AOS 100 パラメーター送信前::: ,  strCommandG = " + strCommandG);

                // パラメーター送信
                byte[] byBuff = strCommandG.getBytes(AppCharset.SHIFT_JIS);
                //byte[] byBuff = strCommandG.getBytes();
                connection.send(byBuff, byBuff.length);

                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                Log.d(TAG, "骨密度 AOS 100 = " + btRet);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: キャンセル = " + receiveRet);
                    return;
                }

                Log.d(TAG, "骨密度 AOS 100: ReceiveParam 値取得：：：" + pNum + "," + pName +
                        "," + pSex + "," + pSng);

                Log.d(TAG, "骨密度 AOS 100: byBuff Arrays.toString, new Strin ★★★★★★★★★★" +
                        Arrays.toString(byBuff) + "," + new String(byBuff));

                // ========= STX チェック ( ﾃｷｽﾄ開始 ) =========
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "骨密度 AOS 100】取得データ：：： strData = " + strData);

                // strData の　先頭の文字が VB の Chr(2) => Java 0x02 => 16進数
                String Left_strData_01_s = strData.substring(0, 1);
                byte[] Left_strData_01_b = Left_strData_01_s.getBytes();
                Log.d(TAG, "骨密度 AOS 100】取得データ：：： Left_strData_01_b = " + Left_strData_01_b);

                // 比較用文字
                byte[] hikaku_b = new byte[1];
                hikaku_b[0] = 0x02;

                // 先頭の文字が、 STX (ﾃｷｽﾄ開始) 0x02 じゃなかったら　エラー処理
                if (!(Arrays.equals(Left_strData_01_b, hikaku_b))) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受付付加");
                    return;
                }

                // ========= 受付番号 チェック =========
                // strData の　２文字目から　切り出し
                // String MidStr = strData.substring(2);
                String MidStr = strData.substring(1);

                // VB の Val 変換
                String Mid_strData_01 = MidStr.replaceAll("[^\\d]", "");
                String Temp_pNum = pNum.replaceAll("[^\\d]]", "");

                Log.d(TAG, "骨密度 AOS 100: " + "Mid_strData_01:::値:::" + Mid_strData_01 + "....Temp_pNum:::値:::" + Temp_pNum);

                if (!(Mid_strData_01.equals(Temp_pNum))) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;

                    Log.d(TAG, "骨密度 AOS 100: [Recv]" + "Mid_strData_01:::" + Mid_strData_01 + ":::Temp_pNum:::" + Temp_pNum);

                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信番号違い キャンセル" +
                            Arrays.toString(byBuff) + "," + new String(byBuff));

                    return;
                }

                // 該当箇所まで　スキップ
                for (int ii = 0; ii <= 13; ii++) {
                    //for (int ii = 1; ii <= 14; ii++) {
                    btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                    Log.d(TAG, "骨密度 AOS 100 for文内 vbCrLf 付き  = " + btRet);

                    if (resultSize.get() <= 0) {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "骨密度 AOS 100: キャンセル for文 内 if  = " + receiveRet);
                        return;
                    }

                } //========== END for

                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル = " + receiveRet);
                    return;
                }

                //============= 値取得 receiveData[1]
                //strData =  ByteUtil.bytesToString(status, 0, resultSize.get()).trim();
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                receiveData[1] = strData; // 骨密度

                Log.d(TAG, "骨密度 AOS 100: = receiveData[1] " + receiveData[1]);

                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル receiveData[1] 骨密度　取得  = " + receiveRet);
                    return;
                }

                // 同年比較
                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル = " + receiveRet);
                    return;
                }

                //============= 値取得 receiveData[2]
                //strData =  ByteUtil.bytesToString(status, 0, resultSize.get()).trim();
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                receiveData[2] = strData;

                Log.d(TAG, "骨密度 AOS 100: = receiveData[2] " + receiveData[2]);

                // 該当箇所までスキップ
                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル = " + receiveRet);
                    return;
                }

                // 最大骨塩量
                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル = " + receiveRet);
                    return;
                }

                //============= 値取得 receiveData[3]
                //strData =  ByteUtil.bytesToString(status, 0, resultSize.get()).trim();
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                receiveData[3] = strData;

                Log.d(TAG, "骨密度 AOS 100: = receiveData[3] " + receiveData[3]);

                // ETX チェック
                btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf); // vbCrLf 付き
                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受信キャンセル [ETX チェック] = " + receiveRet);
                    return;
                }

                //strData =  ByteUtil.bytesToString(status, 0, resultSize.get()).trim();
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());

                Log.d(TAG, "骨密度 AOS 100: = strData " + strData);

                // 0x03
                String Chr_3 = strData.substring(0, 1);
                byte[] bx_03 = new byte[1];
                bx_03 = Chr_3.getBytes();

                // 比較用
                byte[] tx_03 = new byte[1];
                tx_03[0] = 0x03;

                if (!(Arrays.equals(bx_03, tx_03))) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "骨密度 AOS 100: [Recv] 受付不可 if Left(strData, 1) <> Chr(3) Then = ");
                    return;
                }

                // 検査値チェック
                for (int i = 1; i <= 3; i++) {

                    if (StringUtil.isNumeric(receiveData[i]) == false) {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "骨密度 AOS 100: [Recv] 異常終了 = " + receiveData[i]);
                        return;
                    }

                } //========== END for

                // 正常終了
                setReceiveRetWithBtRet(btRet);

            }

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;

        } finally {
            //  '仮想シリアルポート切断()
            connection.close();
        }

    }

    private void btCM200(BluetoothSppConnection connection, String address) {
    }

    private void btDCS600EX(BluetoothSppConnection connection, String address) {
    }

    private void btDTM15(BluetoothSppConnection connection, String address) {
        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        try {
            // 出力項目のクリア
            clearReceiveValues();

            // マスターモードで仮想シリアルポート接続
            int btRet = connection.connect(address);
            if (btRet == StatusConstants.BT_SUCCESS) {
                // Recv開始と同時にDTM-15の蓄積データが送られてくるので
                // Recv開始から一定時間以内に受け取ったデータは無視する
                long startTimeMillis = System.currentTimeMillis();
                long diffTimeMillis = System.currentTimeMillis() - startTimeMillis;
                while (diffTimeMillis < 5000L) {
                    // 測定結果受信
                    Arrays.fill(status, (byte) 0);
                    btRet = connection.receiveDeviceValue(status, resultSize);
                    diffTimeMillis = System.currentTimeMillis() - startTimeMillis;
                    if (resultSize.get() <= 0) {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        return;
                    }
                    Log.d(TAG, "btDTM15: diff time millis from start = " + diffTimeMillis);
                }
                String value = ByteUtil.bytesToString(status, 0, 5).trim();
                if (StringUtil.isNumeric(value)) {
                    // 測定結果格納
                    receiveData[1] = value;    // 腹囲
                } else {
                    receiveRet = StatusConstants.RET_ERR_MEASURE;
                    return;
                }
            }
            setReceiveRetWithBtRet(btRet);
        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }
    }

    /**
     * 　体組成計　DC250
     *
     */
    private void btDC250T(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        int btRet = 0;

        try {
            // 出力項目のクリア
            clearReceiveValues();
            // マスターモードで仮想シリアルポート接続
            btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                // 測定結果取得
                btRet = connection.receiveDeviceValue(status, resultSize);
                System.out.println("btRet:::値:::" + btRet);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "btDC250T 身体計】［receiveRet］受信キャンセル" + receiveRet);
                    return;
                }

                String strResult = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "strResult :::" + strResult);

                // === 値取得 ===
                double value_01_d = ByteUtil.bytesToDouble(status, 34 - 1, 7); // 身長
                double value_02_d = ByteUtil.bytesToDouble(status, 42 - 1, 7); // 体重
                double value_03_d = ByteUtil.bytesToDouble(status, 55 - 1, 4); // 体脂肪

                /*
                String value_01 = String.valueOf(value_01_d);
                String value_02 = String.valueOf(value_02_d);
                String value_03 = String.valueOf(value_03_d);
                 */

                String value_01 = String.format("%.1f",value_01_d);
                String value_02 = String.format("%.1f",value_02_d);
                String value_03 = String.format("%.1f",value_03_d);

                Log.d(TAG, "String型: value_01" + value_01);
                Log.d(TAG, "String型: value_02" + value_02);
                Log.d(TAG, "String型: value_03" + value_03);

                if (StringUtil.isNumeric(value_01) &&
                        StringUtil.isNumeric(value_02) &&
                        StringUtil.isNumeric(value_03)) {

                    // 値格納
                    receiveData[1] = value_01.trim(); // 身長,値
                    receiveData[2] = value_02.trim(); // 体重,値

                    Log.d(TAG, "値取得:  receiveData[1]" +  receiveData[1]);
                    Log.d(TAG, "値取得:  receiveData[2]" +  receiveData[2]);

                    //============= 体脂肪
                    String taishibou_value = value_03.trim(); // 体脂肪,値
                    if(taishibou_value.equals("0.0") || taishibou_value.equals("0")) {
                        taishibou_value = "";
                        receiveData[3] = taishibou_value; // 体脂肪,値 格納
                        Log.d(TAG, "値取得:  receiveData[3]" +  receiveData[3]);
                    } else {
                        receiveData[3] = taishibou_value; // 体脂肪,値 格納
                        Log.d(TAG, "値取得:  receiveData[3]" +  receiveData[3]);
                    }

                    // 仮想シリアルポート切断()
                    setReceiveRetWithBtRet(btRet);

                } else {

                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "btDC250T: 値格納 異常終了 =   検査チェック " + receiveRet);
                    return;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;

        } finally {
            // コネクト閉じる
            connection.close();
        }

    }

    /**
     * BtDC250PCM
     * 体組成計（DC-250）から測定値を取得する（PCモードタイプ）
     *
     * 引数      ：変数名     属性            I/O  項目名
     * pMode      String     I             検査モード（0:身長体重 1:体組成計）
     * pSbt       String     I             性別
     * pAge       String     I             年齢
     *
     * @param address
     */
    private void btDC250PCM(BluetoothSppConnection connection, String address) {
        byte[] byBuff;                                      // 送信バッファ
        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        int btRet;
        int intExitFlag;
        int i;
        String strCommand;
        String strAge;
        String strData;
        String str_CrLf;

        try {

            String pMode = receiveParam[1];     // 1.検査モード
            String pSbt = receiveParam[2];      // 2.性別
            String pAge = receiveParam[3];      // 3.年齢

            Log.d(TAG, "btDC250PCM 】pMode = " + pMode);
            Log.d(TAG, "btDC250PCM 】pSbt = " + pSbt);
            Log.d(TAG, "btDC250PCM 】pAge = " + pAge);

            // 出力項目のクリア
            clearReceiveValues();

            // 'コマンド
            char vb_Cr = 0x0d; // vbCr(13)
            char vb_Lf = 0x0a; // vbLf(10)
            strCommand = String.valueOf(vb_Cr) + String.valueOf(vb_Lf);

            // CRLF 文字列作成
            char J_C_Cr = 0x0d; // Cr
            char J_C_Lf = 0x0a; // Lf
            str_CrLf = String.valueOf(J_C_Cr) + String.valueOf(J_C_Lf);

            // '年齢（3桁を2桁に）
            if(!(pAge.equals(""))) {

                if(BtCInt(pAge) != 0) {
                    // VB => strAge = Right(JPadLeft(CStr(BtCInt(pAge)), 2, "0"), 2)
                    int tmp_i_pAge = BtCInt(pAge);
                    String tmp_s_pAge = Integer.valueOf(tmp_i_pAge).toString();
                    tmp_s_pAge = String.format("%2s", tmp_s_pAge).replace(" ", "0");
                    //22/08/17 pAge = tmp_s_pAge.substring(1);
                    pAge = tmp_s_pAge.substring(0,2);
                    Log.d(TAG, "btDC250PCM 】pAge = " + pAge);
                } else {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "btDC250PCM 】［receiveRet］受信キャンセル" + receiveRet);
                    return;
                }

            } else {
                strAge = "30";
            }

            // ====== マスターモードで仮想シリアルポート接続
            btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {

                // ①PCモード切替
                intExitFlag = 0;
                while (intExitFlag == 0) {

                    // バイトコードへ変換
                    String strCommand_Temp_01 = "M1" + strCommand;
                    byBuff = strCommand_Temp_01.getBytes(AppCharset.SHIFT_JIS);
                    // === コマンド　送信 ==
                    connection.send(byBuff, byBuff.length);
                    // === 測定結果　受信 ===
                    btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                    // キャンセル
                    if (resultSize.get() <= 0) {

                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "btDC250PCM: キャンセル =  ①PCモード切替 1" + receiveRet);
                        return;
                    }

                    // ========= 測定結果格納
                    strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                    Log.d(TAG, "btDC250PCM 】①PCモード切替 strData = " + strData);

                    if(strData.substring(0,1).equals("@")) {
                        intExitFlag = 1;
                    } else {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "btDC250PCM 】Recv］受付不可 strData  ①PCモード切替 2" + strData);
                        return;
                    }

                } // ========= END while

                // '②風袋量設定（1kg固定）
                intExitFlag = 0;
                while(intExitFlag == 0) {

                    // バイトコードへ変換
                    String strCommand_Temp_02 = "D001.0" + strCommand;
                    byBuff = strCommand_Temp_02.getBytes(AppCharset.SHIFT_JIS);
                    // === コマンド　送信 ==
                    connection.send(byBuff, byBuff.length);
                    // === 測定結果　受信 ===
                    btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                    // キャンセル
                    if (resultSize.get() <= 0) {

                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "btDC250PCM: キャンセル = ②風袋量設定（1kg固定） 1 " + receiveRet);
                        return;
                    }

                    // ========= 測定結果格納
                    strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                    Log.d(TAG, "btDC250PCM 】'②風袋量設定（1kg固定） strData = " + strData);

                    if(strData.substring(0, 9).equals("D0,Pt,1.0")) {
                        intExitFlag = 1;
                    } else {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "btDC250PCM 】Recv］受付不可 strData ②風袋量設定（1kg固定）2 " + strData);
                        return;
                    }

                } // ========= END while

                // '体組成計モードの場合----------------------------------------------------------
                if(pMode.equals("1")) {
                    // '③性別設定
                    intExitFlag = 0;
                    while(intExitFlag == 0) {
                        // バイトコードへ変換
                        String strCommand_Temp_03 = "D1" + pSbt + strCommand;
                        byBuff = strCommand_Temp_03.getBytes(AppCharset.SHIFT_JIS);
                        // === コマンド　送信 ==
                        connection.send(byBuff, byBuff.length);
                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        // キャンセル
                        if (resultSize.get() <= 0) {

                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM: キャンセル ③性別設定 1 = " + receiveRet);
                            return;
                        }

                        // ========= 測定結果格納
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "btDC250PCM 】'体組成計モードの場合 strData = " + strData);

                        if(strData.substring(0, 6).equals("D1,GE,")) {
                            intExitFlag = 1;
                        } else {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM 】Recv］受付不可 strData ③性別設定 2 " + strData);
                            return;
                        }

                    } // ========= END while

                    // '④年齢設定
                    intExitFlag = 0;
                    while (intExitFlag == 0) {

                        // バイトコードへ変換
                        String strCommand_Temp_04 = "D4" + pAge + strCommand;
                        byBuff = strCommand_Temp_04.getBytes(AppCharset.SHIFT_JIS);
                        // === コマンド　送信 ==
                        connection.send(byBuff, byBuff.length);
                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        // キャンセル
                        if (resultSize.get() <= 0) {

                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM: キャンセル = ④年齢設定 " + receiveRet);
                            return;
                        }

                        // ========= 測定結果格納
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "btDC250PCM 】'④年齢設定 strData = " + strData);

                        if(strData.substring(0,6).equals("D4,AG,")) {
                            intExitFlag = 1;
                        } else {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM 】Recv］受付不可 strData ④年齢設定 2 " + strData);
                            return;
                        }

                    } // ========= END while

                    // ⑤体型設定（スタンダード固定）
                    intExitFlag = 0;
                    while (intExitFlag == 0) {

                        // バイトコードへ変換
                        String strCommand_Temp_05 = "D20" + strCommand;
                        byBuff = strCommand_Temp_05.getBytes(AppCharset.SHIFT_JIS);
                        // === コマンド　送信 ==
                        connection.send(byBuff, byBuff.length);
                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        // ========= 測定結果格納
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "btDC250PCM 】'⑤体型設定（スタンダード固定） strData = " + strData);

                        if(strData.substring(0, 7).equals("D2,Bt,0")) {
                            intExitFlag = 1;
                        } else {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM 】Recv］受付不可 strData ⑤体型設定（スタンダード固定） 1 " + strData);
                            return;
                        }
                    } // ========= END while

                    // ⑥測定開始
                    intExitFlag = 0;
                    while(intExitFlag == 0) {

                        // バイトコードへ変換
                        String strCommand_Temp_06 = "G" + strCommand;
                        byBuff = strCommand_Temp_06.getBytes(AppCharset.SHIFT_JIS);
                        // === コマンド　送信 ==
                        connection.send(byBuff, byBuff.length);
                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        // キャンセル
                        if (resultSize.get() <= 0) {

                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM: キャンセル = ④年齢設定 " + receiveRet);
                            return;
                        }

                        // ========= 測定結果格納
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "btDC250PCM 】' ⑥測定開始 strData = " + strData);

                        if(strData.substring(0, 2).equals("S6")) {
                            intExitFlag = 1;
                        } else {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM 】Recv］受付不可 strData ⑥測定開始 1 " + strData);
                            return;
                        }

                    } // ========= END while

                    // ⑦結果待ち
                    intExitFlag = 0;
                    while (intExitFlag == 0) {
                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        // キャンセル
                        if (resultSize.get() <= 0) {

                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM: キャンセル = ④年齢設定 " + receiveRet);
                            return;
                        }

                        // ========= 測定結果格納
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "btDC250PCM 】' ⑦結果待ち strData = " + strData);

                        if(strData.substring(0, 5).equals("{0,16")) {
                            intExitFlag = 1;
                        }

                    } // ========= END while

                } else {
                    // '身長体重モードの場合----------------------------------------------------------
                    // ③体重測定
                    intExitFlag = 0;
                    while (intExitFlag == 0) {
                        // バイトコードへ変換
                        String strCommand_Temp_07 = "E" + strCommand;
                        byBuff = strCommand_Temp_07.getBytes(AppCharset.SHIFT_JIS);
                        // === コマンド　送信 ==
                        connection.send(byBuff, byBuff.length);

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        // キャンセル
                        if (resultSize.get() <= 0) {

                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM: キャンセル = 身長体重モードの場合 ③体重測定 1 " + receiveRet);
                            return;
                        }

                        // ========= 測定結果格納
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "btDC250PCM 】'身長体重モードの場合 体重測定 strData = " + strData);

                        if(strData.substring(0, 2).equals("S6")) {
                            intExitFlag = 1;
                        } else {
                            // キャンセル
                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM 】Recv］受付不可 strData 身長体重モードの場合 ③体重測定  2 " + strData);
                            return;
                        }

                    } // ========= END while

                    // ④結果待ち
                    intExitFlag = 0;
                    while (intExitFlag == 0) {

                        // === 測定結果　受信 ===
                        btRet = connection.receiveDeviceValue(status, resultSize, str_CrLf);

                        // キャンセル
                        if (resultSize.get() <= 0) {

                            receiveRet = StatusConstants.RET_ERR_CANCEL;
                            Log.d(TAG, "btDC250PCM: キャンセル = 身長体重モードの場合 ④結果待ち " + receiveRet);
                            return;
                        }

                        // ========= 測定結果格納
                        strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                        Log.d(TAG, "btDC250PCM 】'身長体重モードの場合  ④結果待ち strData = " + strData);

                        if(strData.substring(0, 5).equals("{0,16")) {
                            intExitFlag = 1;
                        }

                    } // ========= END while

                } // =================================== END if

                // ========= 測定結果格納
                strData = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "btDC250PCM 】'「身長」 「体重」「体脂肪」 strData = " + strData);

                // 身長
                i = strData.indexOf("Hm,");
                if(i != 0) {
                    receiveData[1] = String.valueOf(Double.parseDouble(strData.substring(i + 3, i + 3 + 5)));
                    Log.d(TAG, "測定結果格納 receiveData[1] :::" + receiveData[1]);
                } else {
                    receiveData[1] = "";
                }

                // 体重
                i = strData.indexOf("Wk,");
                if(i != 0) {
                    String receiveData_tmp_02 = strData.substring(i + 3, i + 3 + 5);
                    Log.d(TAG, "測定結果格納 receiveData_tmp_02 :::" + receiveData_tmp_02);
                    //if(receiveData_tmp_02.endsWith(".")) {
                    if(receiveData_tmp_02.endsWith(",")) {
                        receiveData_tmp_02 = receiveData_tmp_02.substring(0, receiveData_tmp_02.length() - 1);
                        receiveData[2] = String.valueOf(Double.parseDouble(receiveData_tmp_02));
                        Log.d(TAG, "測定結果格納 receiveData[2] :::" + receiveData[2]);

                    } else if(receiveData_tmp_02.endsWith("F") || receiveData_tmp_02.endsWith("M")) {
                        receiveData_tmp_02 = receiveData_tmp_02.substring(0, 2);
                        receiveData[2] = String.valueOf(Double.parseDouble(receiveData_tmp_02));
                        Log.d(TAG, "測定結果格納 receiveData[2] :::" + receiveData[2]);

                    } else {
                        receiveData[2] = String.valueOf(Double.parseDouble(strData.substring(i + 3, i + 3 + 5)));
                        Log.d(TAG, "測定結果格納 receiveData[2] :::" + receiveData[2]);
                    }

                } else {
                    receiveData[2] = "";
                }

                // 体脂肪
                if(strData.contains("FW,")) {
                    i = strData.indexOf("FW,");
                    //receiveData[3] = String.valueOf(Double.parseDouble(strData.substring(i + 3, i + 3 + 4)));
                    //Log.d(TAG, "測定結果格納 receiveData[3] :::" + receiveData[3]);

                    String receiveData_tmp_03 = strData.substring(i + 3, i + 3 + 4);
                    if(receiveData_tmp_03.endsWith(",")) {
                        receiveData_tmp_03 = receiveData_tmp_03.substring(0, receiveData_tmp_03.length() - 1);
                        receiveData[3] = String.valueOf(Double.parseDouble(receiveData_tmp_03));
                        Log.d(TAG, "測定結果格納 receiveData[3] :::" + receiveData[3]);
                    } else {
                        receiveData[3] = String.valueOf(Double.parseDouble(strData.substring(i + 3, i + 3 + 4)));
                        Log.d(TAG, "測定結果格納 receiveData[3] :::" + receiveData[3]);
                    }
                } else {
                    receiveData[3] = "";
                }

                // === '2011/01/24 Y.O 検査値チェック
                if (StringUtil.isNumeric(receiveData[1]) == false) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "BtDC250PCM:receiveData[1] 異常終了 =   検査チェック "
                            + receiveData[1]);
                    return;
                }

                if (StringUtil.isNumeric(receiveData[2]) == false) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "BtDC250PCM:receiveData[2] 異常終了 =   検査チェック "
                            + receiveData[2]);
                    return;
                }

                if (!receiveData[3].isEmpty()) {
                    if (StringUtil.isNumeric(receiveData[3]) == false) {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "BtDC250PCM:receiveData[3] 異常終了 =   検査dcチェック "
                                + receiveData[3]);
                        return;
                    }
                }
                // 正常終了
                setReceiveRetWithBtRet(btRet);
            } else {

                // キャンセル
                receiveRet = StatusConstants.RET_ERR_CANCEL;
                Log.d(TAG, "BtDC250PCM: BT_SUCCESS エラー:::  = ");
                return;
            }

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }  finally {
            // コネクト close
            connection.close();
            // setReceiveRetWithBtRet(btRet);
        }

    }

    /**    MODULE    ：btVS2500RECV
     *     VBソース btVS3000 と同じ
     *     概要      ：CAVI（VaSera VS2500）から測定値を取得する
     *     引数      ：変数名     属性            I/O  項目名
     *     設定項目  ：ReceiveData(1)  R-CAVI
     *                ReceiveData(2)  L-CAVI
     *                ReceiveData(3)  R-ABI
     *                ReceiveData(4)  L-ABI
     *     2022_04_22 作成 新規　：　夏目　
     */
    private void btVS3000RECV(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ
        int btRet = 0;

        try {
            Log.d(TAG, "【BtVS btVS3000RECV】try 文　内開始 ★★★★★★★ ::: " + btRet);
            // 出力項目のクリア
            clearReceiveValues();

            // ====== マスターモードで仮想シリアルポート接続
            btRet = connection.connect(address);
            Log.d(TAG, "【BtVS btVS3000RECV】btVS3000RECV　btRet = シリアルポート接続開始 ::: " + btRet);

            if (btRet == StatusConstants.BT_SUCCESS) {

                // 測定結果取得
                btRet = connection.receiveDeviceValue(status, resultSize);
                Log.d(TAG, "btVS3000RECV 値取得部分 value_01 :::" + btRet);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "【BtVS btVS3000RECV】btVS3000RECV　キャンセル = " + receiveRet);
                    return;
                }

                String value_01 = ByteUtil.bytesToString(status, 81 - 1, 4).trim(); // 身長
                String value_02 = ByteUtil.bytesToString(status, 86 - 1, 4).trim(); // 体重
                String value_03 = ByteUtil.bytesToString(status, 101 - 1, 4).trim(); // 体脂肪
                String value_04 = ByteUtil.bytesToString(status, 105 - 1, 4).trim(); // 体脂肪

                if(StringUtil.isNumeric(value_01) &&
                        StringUtil.isNumeric(value_02) &&
                        StringUtil.isNumeric(value_03) &&
                        StringUtil.isNumeric(value_04)) {
                    //=== 値取得
                    receiveData[1] = value_01; // R-CAVI
                    Log.d(TAG, "btVS3000RECV 値取得部分 value_01 :::" + value_01);
                    receiveData[2] = value_02; // L-CAVI
                    Log.d(TAG, "btVS3000RECV 値取得部分 value_02 :::" + value_02);
                    receiveData[3] = value_03; // R-ABI
                    Log.d(TAG, "btVS3000RECV 値取得部分 value_03 :::" + value_03);
                    receiveData[4] = value_04; // L-ABI
                    Log.d(TAG, "btVS3000RECV 値取得部分 value_04 :::" + value_04);
                } else {
                    receiveRet = StatusConstants.RET_ERR_MEASURE;
                    return;
                }

                // 仮想シリアルポート切断
                setReceiveRetWithBtRet(btRet);

            } // ===  if btRet == StatusConstants.BT_SUCCESS === END


        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }  finally {
            // コネクト close
            connection.close();
            // setReceiveRetWithBtRet(btRet);
        }

    }

    private void btACCUREF_R800(BluetoothSppConnection connection, String address) {
    }

    private void btRKF2(BluetoothSppConnection connection, String address) {
    }

    /**
     * BtSP350COPD_sub2
     * pData      Byte()           I   受信データ
     * pKomoku    String           I   項目名
     * pSeq       Integer          I   項目の順番
     *
     * 戻り値    ：取得した項目
     */
    private String BtSP350COPD_sub2(String pData, String pKomoku, int pSeq) {

        char Chr_9_Temp = 0x09;
        String strKey = pKomoku + String.valueOf(Chr_9_Temp);
        Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2） = Chr_9_Temp:::" + strKey);

        int intPos = 0;
        int intPo2 = 0;
        int i;
        String r_data = "";

        String r = "false";

        // vb ソース
        // ===  intPos = InStr(pData, strKey)
        Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2） = pData:::" + pData);

        intPos = pData.indexOf(strKey);
        Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2） = intPos:::" + intPos);
        if (intPos == 0) {
            return r;
        } // === END if

        for (i = 0; i < pSeq; i++) {
            // VB ソース
            // ===  intPo2 = InStr(intPos + 1, pData, Chr(9))
            intPo2 = pData.indexOf(0x09, intPos + 1);
            Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2） for 内  = intPo2:::" + intPo2);
            if (intPo2 == 0) {
                return r;
            } // === END if
            intPos = intPo2;
            Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2） for 内 if 内 = intPos:::" + intPos);
        } // === END for

        // VB ソース
        // ===  intPo2 = InStr(intPos + 1, pData, Chr(9))
        intPo2 = pData.indexOf(0x09, intPos + 1);
        Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2） for 抜け後 if = intPo2:::" + intPo2);
        if (intPo2 == 0) {
            return r;
        } // === END if

        intPos += 1;
        Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2）= intPos += 1:::" + intPos);
        Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2）= intPos2 += 1:::" + intPo2);

        // === VB ソース BtSP350COPD_sub2 = Mid(pData, intPos, in
        //BtSP350COPD_sub2 = pData.substring(intPos, intPo2 - intPo2);
        Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2）= pData function return 前:::" + pData);
        Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2）= intPos, intPo2 - intPos 前 値:::" + (intPos + (intPo2 - intPos)));

        r_data = pData.substring(intPos, intPos + (intPo2 - intPos));
        Log.d(TAG, "肺活量計 function（BtSP350COPD_sub2）= ＊＊＊　返り値 ＊＊＊ r_data  前 値:::" + r_data);

        return r_data;

    } // =========================  BtSP350COPD_sub2 END

    /**
     * SP 350 （肺機能）値取得　関数
     * @param pData
     * @param pSize
     */
    private void BtSP350COPD_sub1(byte[] pData, int pSize) {

        String strData;
        if (pSize >= 10) {

            String tmp = ByteUtil.bytesToString(pData, 7 - 1, 4);
            Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1） = tmp" + tmp);

            //   switch (ByteUtil.bytesToString(pData, 7 - 1, 4)) {
            switch (tmp) {
                case "0201":
                    strData = ByteUtil.bytesToString(pData, 0, pSize);
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1） =  case:0201 strData:::" + strData);

                    receiveData[5] = BtSP350COPD_sub2(strData, "VC", 2); // 肺活量予測値
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1）0201 receiveData[5] :::" + receiveData[5]);

                    receiveData[1] = BtSP350COPD_sub2(strData, "VC", 3); // 肺活量
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1）0201 receiveData[1] :::" + receiveData[1]);

                    receiveData[2] = BtSP350COPD_sub2(strData, "VC", 4); // %肺活量
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1）0201 receiveData[2] :::" + receiveData[2]);

                    break;

                case "0202":
                    strData = ByteUtil.bytesToString(pData, 0, pSize);
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1） =  case:0202 strData:::" + strData);

                    receiveData[6] = BtSP350COPD_sub2(strData, "FVC", 3); // 努力肺活量
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1）0202 receiveData[6] :::" + receiveData[6]);

                    receiveData[3] = BtSP350COPD_sub2(strData, "FEV1", 3); // 一秒量
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1）0202 receiveData[3] :::" + receiveData[3]);

                    receiveData[4] = BtSP350COPD_sub2(strData, "FEV1%G", 3); // 一秒率 FEV1%G
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1）0202 receiveData[4] :::" + receiveData[4]);

                    receiveData[7] = BtSP350COPD_sub2(strData, "FEV1", 2); // 一秒量 予測値
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1）0202 receiveData[7] :::" + receiveData[7]);

                    receiveData[8] = BtSP350COPD_sub2(strData, "FEV1", 4); // %一秒量
                    Log.d(TAG, "肺活量計 function（BtSP350COPD_sub1）0202 receiveData[8] :::" + receiveData[8]);

                    break;

                default:
                    break;
                    /*
                case "0301":
                    break;
                     */

            } // ====== END switch

        } // ====== END if

    } // ===================== END BtSP350COPD_sub1

    // endregion

    // region Bluetooth連携機器個別の送信処理

    private int btVS1000(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btVS1000: ");
        // TODO 使用→CAVI：健康医学予防協会 -- 新潟健診プラザ１
        return 0;
    }

    private int btVS1500(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btVS1500: ");
        // TODO 使用→CAVI：健康医学予防協会 -- 長岡健診センター１
        return 0;
    }

    /**    MODULE    ：BtVS2500SEND
     *     概要      ：CAVI（VaSera VS2500）へデータを送信する
     *  　　引数      ：変数名     属性            I/O  項目名
     *                　pNum       String           I   検診者番号
     *                　pName      String           I   氏名
     *                　pSex       String           I   性別
     *           　　　　pSng       String           I   生年月日
     *                  pAge       String           I   年齢
     *  　　　　　　　　　pHeight    String           I   身長
     *     　　　　　　　pWeight    String           I   体重
     *     戻り値    　　RET_SUCCESS         = 正常終了
     *                 RET_ERR_MEASURE     = エラー
     */
    private int btVS3000SEND(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btVS3000SEND: ");

        // TODO 使用→CAVI：多数

        byte[] byBuff;	// 送信バッファ
        String strSend; // 送信データ用
        int intRet;

        String pNum = receiveParam[1];       // 1.検診者番号
        String pName = receiveParam[2];      // 2.氏名
        String pSex = receiveParam[3];       // 3.性別
        String pSng = receiveParam[4];       // 4.生年月日
        String pAge = receiveParam[5];       // 5.年齢
        String pHeight = receiveParam[6];    // 6.身長
        String pWeight = receiveParam[7];    // 7.体重
        String pJcd = receiveParam[8];       // 8.受診者コード

        // パラメーター
        strSend = "";

        // === 検査番号(6)
        String val_pNum = pNum.replaceAll("[^\\d]", "").trim();
        Log.d(TAG, "CAVI [btVS2500SEND] val_pNum :::" + val_pNum);
        // 左にパディング 0埋め (6) VB ソース => JPadLeft
        String format_pNum = String.format("%6s", val_pNum).replace(" ", "0");
        Log.d(TAG, "CAVI [btVS2500SEND] format_pNum :::" + format_pNum);
        // 検査番号を挿入
        strSend += format_pNum;

        // === 受診者コード(10)
        String val_pJcd = pJcd.replaceAll("[^\\d]", "").trim();
        Log.d(TAG, "CAVI [btVS2500SEND] val_pJcd :::" + val_pJcd);

        String format_pJcd = String.format("%10s", val_pJcd).replace(" ", "0");
        Log.d(TAG, "CAVI [btVS2500SEND] format_pJcd :::" + format_pJcd);
        // 受診者コードを挿入
        strSend += format_pJcd;

        // === 氏名カナ
        String right_pName = String.format("%-24s", pName);
        strSend += right_pName.substring(0, 24);

        // === 生年月日（YYYYMMDD）
        strSend += pSng;

        // === 性別(1)
        if (pSex.equals("1")) {
            strSend += "M";
        } else {
            strSend += "F";
        }

        // === 年齢(3)
        // VB ソース :::  strSend &= JPadRight(CStr(Val(pAge)), 3)
        String Val_pAge = pAge.replaceAll("[^\\d]", "").trim();
        Log.d(TAG, "CAVI [btVS2500SEND] = 変数出力:: Val_pAge " + Val_pAge);
        // 年齢 値取得
        strSend += String.format("%-3s", Val_pAge);

        // === 体重(5)
        strSend += String.format("%-5s", pWeight.trim());
        Log.d(TAG, "CAVI [btVS2500SEND] = 変数出力:: pWeight ：：： " + pWeight);

        // === 身長(5)
        strSend += String.format("%-5s", pHeight.trim());
        Log.d(TAG, "CAVI [btVS2500SEND] = 変数出力:: pHeight ：：： " + pHeight);

        Log.d(TAG, "CAVI [btVS2500SEND] = 変数出力:: strSend ：：： " + strSend);

        // ===== マスターモードで仮想シリアルポート接続
        intRet = connection.connect(address);

        if (intRet == StatusConstants.BT_SUCCESS) {

            try {
                byBuff = strSend.getBytes(AppCharset.SHIFT_JIS);
                // byBuff = strSend.getBytes();
                connection.send(byBuff, byBuff.length);
                Thread.sleep(500); // 0.5 秒スリープ
                Log.d(TAG, "CAVI [btVS2500SEND] = try 内  送信 OK ：：： ");
            }catch (InterruptedException e) {
                e.printStackTrace();

            } finally {
                // 仮想シリアルポート切断()
                setReceiveRetWithBtRet(intRet);
                //connection.close();
            }
        }

        switch (intRet) {
            case StatusConstants.BT_SUCCESS:                // 正常終了
                return StatusConstants.RET_SUCCESS;
            case StatusConstants.BT_FAILED:                 // デバイス情報設定失敗
                return StatusConstants.RET_ERR_DEVFAILED;
            case StatusConstants.BT_DRIVER_ERROR:           // ドライバーエラー
                return StatusConstants.RET_ERR_DRIVER;
            case StatusConstants.BT_FUNCTION_UNSUPPORT:     // 未サポートエラー
            default:
                return StatusConstants.RET_ERR_UNSUPPORT;
        }

    }

    private int btECG1450(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btECG1450: ");
        // 使用されていないため移植保留
        return 0;
    }

    private int btFCP4721(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btFCP4721: ");
        // 使用されていないため移植保留
        return 0;
    }

    private int btFCP4521(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btFCP4521: ");
        byte[] byBuff;
        int intSendSize;
        String strSend;

        String pNum = receiveParam[1];     // 1.番号     -- 検査番号
        String pSeq = receiveParam[2];     // 2.個人SEQ  -- 予約番号 (!!未使用!!)
        String pName = receiveParam[3];    // 3.氏名     -- 氏名(カナ)
        String pSex = receiveParam[4];     // 4.性別     --
        String pSng = receiveParam[5];     // 5.生年月日 --
        String pAge = receiveParam[6];     // 6.年齢     --

        // パラメータ
        // 先頭部(JIS+FUJI)
        strSend = "A0FUJI";
        // 氏名(20+SI/SO)
        strSend += (char) (0xE) + StringUtil.padSpaceRight(pName, 20) + (char) (0xF);
        // ID
        strSend += StringUtil.padZeroLeft(pNum.trim(), 10);
        // 生年月日(YYYY.MM.DD)
        strSend += pSng.substring(0, 0 + 4) + "." + pSng.substring(4, 4 + 2) + "." + pSng.substring(6, 6 + 2);
        // 性別
        strSend += pSex.equals("1") ? "M" : "F";
        // 受診番号
        strSend += StringUtil.padZeroLeft(pNum.trim(), 6);
        // 年齢(下位1桁削除)
        strSend += StringUtil.padZeroLeft(pAge.trim(), 3).substring(0, 2);
        // 受診日(未使用)
        strSend += "0000.00.00";
        // その他
        strSend += "    " + (char) (3);
        // [STX] + (データ) + LRC
        strSend = (char) (2) + appendBCC(strSend, 1);

        // マスターモードで仮想シリアルポート接続
        int intRet = connection.connect(address);

        if (intRet == StatusConstants.BT_SUCCESS) {
            byBuff = strSend.getBytes(AppCharset.SHIFT_JIS);
            intRet = connection.send(byBuff, byBuff.length);

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        switch (intRet) {
            case StatusConstants.BT_SUCCESS:                // 正常終了
                return StatusConstants.RET_SUCCESS;
            case StatusConstants.BT_FAILED:                 // デバイス情報設定失敗
                return StatusConstants.RET_ERR_DEVFAILED;
            case StatusConstants.BT_DRIVER_ERROR:           // ドライバーエラー
                return StatusConstants.RET_ERR_DRIVER;
            case StatusConstants.BT_FUNCTION_UNSUPPORT:     // 未サポートエラー
            default:
                return StatusConstants.RET_ERR_UNSUPPORT;
        }
    }

    private int btFUKUDA(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btFUKUDA: ");
        byte[] byBuff;
        int intSendSize;
        String strSend;

        String pNum = receiveParam[1];     // 1.番号     -- 検査番号
        String pSeq = receiveParam[2];     // 2.個人SEQ  -- 予約番号 (!!未使用!!)
        String pName = receiveParam[3];    // 3.氏名     -- 氏名(カナ)
        String pSex = receiveParam[4];     // 4.性別     --
        String pSng = receiveParam[5];     // 5.生年月日 --
        String pAge = receiveParam[6];     // 6.年齢     --

        // パラメータ
        // 先頭部(JIS+FUJI)
        strSend = "A0FUJI";
        // 受診者ID(12)
        strSend += StringUtil.padZeroLeft(pSeq.trim(), 12);
        // 氏名(SI + 24 + SO)
        strSend += (char) (0xE) + StringUtil.padSpaceRight(pName, 24).substring(0, 24) + (char) (0xF);
        // 生年月日(YYYYMMDD)(8)
        strSend += pSng.substring(0, 0 + 4) + pSng.substring(4, 4 + 2) + pSng.substring(6, 6 + 2);
        // 性別
        strSend += pSex.equals("1") ? "M" : "F";
        // 検査番号AccessionID(YYYYMMDD+受診番号+場所)(16)
        strSend += StringUtil.padZeroLeft(pNum.trim(), 16);
        // その他
        strSend += "    " + (char) (3);
        // [STX] + (データ) + LRC
        // [STX] + (データ) + LRC
        strSend = (char) (2) + appendBCC(strSend, 1);

        // マスターモードで仮想シリアルポート接続
        int intRet = connection.connect(address);

        if (intRet == StatusConstants.BT_SUCCESS) {
            byBuff = strSend.getBytes(AppCharset.SHIFT_JIS);
            intRet = connection.send(byBuff, byBuff.length);

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        switch (intRet) {
            case StatusConstants.BT_SUCCESS:                // 正常終了
                return StatusConstants.RET_SUCCESS;
            case StatusConstants.BT_FAILED:                 // デバイス情報設定失敗
                return StatusConstants.RET_ERR_DEVFAILED;
            case StatusConstants.BT_DRIVER_ERROR:           // ドライバーエラー
                return StatusConstants.RET_ERR_DRIVER;
            case StatusConstants.BT_FUNCTION_UNSUPPORT:     // 未サポートエラー
            default:
                return StatusConstants.RET_ERR_UNSUPPORT;
        }
    }

    private int btFUKUDA2(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btFUKUDA2: ");
        byte[] byBuff;
        int intSendSize;
        String strSend;

        String pNum = receiveParam[1];     // 1.番号     -- 検査番号
        String pSeq = receiveParam[2];     // 2.個人SEQ  -- 予約番号 (!!未使用!!)
        String pName = receiveParam[3];    // 3.氏名     -- 氏名(カナ)
        String pSex = receiveParam[4];     // 4.性別     --
        String pSng = receiveParam[5];     // 5.生年月日 --
        String pAge = receiveParam[6];     // 6.年齢     --

        // パラメータ
        // 先頭部(JIS+FUJI)
        strSend = "A0FUJI";
        // 氏名(20+SI/SO)
        strSend += (char) (0xE) + StringUtil.padSpaceRight(pName, 20) + (char) (0xF);
        // ID
        strSend += StringUtil.padZeroLeft(pNum.trim(), 10);
        // 生年月日(YYYY.MM.DD)
        strSend += pSng.substring(0, 0 + 4) + "." + pSng.substring(4, 4 + 2) + "." + pSng.substring(6, 6 + 2);
        // 性別
        strSend += pSex.equals("1") ? "M" : "F";
        // 受診番号
        strSend += StringUtil.padZeroLeft(pNum.trim(), 6);
        // 年齢(下位1桁削除)
        strSend += StringUtil.padZeroLeft(pAge.trim(), 3).substring(0, 2);
        // 受診日(未使用)
        strSend += "0000.00.00";
        // その他
        strSend += "    " + (char) (3);
        // [STX] + (データ) + LRC
        strSend = (char) (2) + appendBCC(strSend, 1);

        // マスターモードで仮想シリアルポート接続
        int intRet = connection.connect(address);

        if (intRet == StatusConstants.BT_SUCCESS) {
            byBuff = strSend.getBytes(AppCharset.SHIFT_JIS);
            intRet = connection.send(byBuff, byBuff.length);

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        switch (intRet) {
            case StatusConstants.BT_SUCCESS:                // 正常終了
                return StatusConstants.RET_SUCCESS;
            case StatusConstants.BT_FAILED:                 // デバイス情報設定失敗
                return StatusConstants.RET_ERR_DEVFAILED;
            case StatusConstants.BT_DRIVER_ERROR:           // ドライバーエラー
                return StatusConstants.RET_ERR_DRIVER;
            case StatusConstants.BT_FUNCTION_UNSUPPORT:     // 未サポートエラー
            default:
                return StatusConstants.RET_ERR_UNSUPPORT;
        }
    }

    private int btVIGOMENT(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btVIGOMENT: ");
        // 使用されていないため移植保留
        return 0;
    }

    private int btFCP7541(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btFCP7541: ");
        // 使用されていないため移植保留
        return 0;
    }

    private int btSREXD32C(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btSREXD32C: ");
        // TODO 使用→胃部X線：日健協 伊勢崎とちぎ -- 巡回３
        return 0;
    }

    private int btQRCONN(BluetoothSppConnection connection, String address) {
        Log.d(TAG, "btQRCONN: ");
        byte[] byBuff;
        int intSendSize;
        String strSend;

        // 入力パラメータ  2010/11/22
        String pSeq = receiveParam[1];     // 1.受診者ID
        String pName = receiveParam[2];    // 2.氏名(カナ)
        String pKNam = receiveParam[3];    // 3.氏名(漢字)
        String pSng = receiveParam[4];     // 4.生年月日
        String pSex = receiveParam[5];     // 5.性別
        String pNum = receiveParam[6];     // 6.検査番号
        // パラメータ
        // 受診者ID(12)
        strSend = StringUtil.padZeroLeft(pSeq.trim(), 12);
        // 氏名(カナ)(30)
        strSend += StringUtil.padSpaceRight(pName, 30);
        // 氏名(漢字)(30)
        strSend += StringUtil.padSpaceRight(pKNam, 30);
        // 生年月日(YYYYMMDD)(8)
        strSend += pSng.substring(0, 0 + 4) + pSng.substring(4, 4 + 2) + pSng.substring(6, 6 + 2);
        // 性別(1) 1 or 2
        strSend += pSex;
        // 検査番号AccessionID(YYYYMMDD+受診番号+場所)(16)
        strSend += StringUtil.padZeroLeft(pNum.trim(), 16);
        // 検査区分(未使用なのでダミー)(3)
        strSend += "   ";

        // マスターモードで仮想シリアルポート接続
        int intRet = connection.connect(address);

        if (intRet == StatusConstants.BT_SUCCESS) {
            byBuff = strSend.getBytes(AppCharset.SHIFT_JIS);
            intRet = connection.send(byBuff, byBuff.length);

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        switch (intRet) {
            case StatusConstants.BT_SUCCESS:                // 正常終了
                return StatusConstants.RET_SUCCESS;
            case StatusConstants.BT_FAILED:                 // デバイス情報設定失敗
                return StatusConstants.RET_ERR_DEVFAILED;
            case StatusConstants.BT_DRIVER_ERROR:           // ドライバーエラー
                return StatusConstants.RET_ERR_DRIVER;
            case StatusConstants.BT_FUNCTION_UNSUPPORT:     // 未サポートエラー
            default:
                return StatusConstants.RET_ERR_UNSUPPORT;
        }
    }

    // endregion

    /**
     * 末尾にBCC(ブロックチェックキャラクタ)を付加した文字列を取得
     *
     * @param data 先頭STXを含まず末尾ETXを含む文字列
     * @param type 0: SUM方式, 1: XOR方式
     * @return BCCを付加した文字列(文字列が空でBCC算出不可能ならば元の文字列)
     */
    private String appendBCC(String data, int type) {
        Byte bcc = getBCC(data, type);
        if (bcc == null) {
            return data;
        }

        int appendedLength = data.getBytes(AppCharset.SHIFT_JIS).length + 1;
        byte[] bytes = ByteUtil.stringToBytes(data, appendedLength, true);
        bytes[bytes.length - 1] = bcc;

        return new String(bytes, AppCharset.SHIFT_JIS);
    }

    /**
     * GetBCC() に相当<br>
     * 付加する文字列ではなくbyteを返す
     *
     * @param data ブロックチェックキャラクタ算出のための文字列
     * @param type 0: SUM方式, 1: XOR方式
     * @return 入力文字列がnullか空ならnull、そうでなければ付加するByte
     */
    private Byte getBCC(String data, int type) {
        if (data == null) {
            return null;
        }

        byte[] byData = data.getBytes(AppCharset.SHIFT_JIS);
        byte byBCC = 0;

        if (0 < byData.length) {
            for (int i = 0; i < byData.length; i++) {
                switch (type) {
                    case 0:
                        byBCC = (byte) (byBCC + byData[i]);
                        break;
                    case 1:
                        byBCC = (byte) (byBCC ^ byData[i]);
                        break;
                }
            }
            return byBCC;
        } else {
            return null;
        }
    }



    /**
     *   SetParity
     *   バイト列の最上位ビットにパリティ情報を付加する
     *
     *    pData      Byte()           I   バイト列
     *    intLen     Integer          I   対象の長さ
     *    pType      Integer         I   パリティ種類(0:Even 1:Od
     *
     *    戻り値　なし
     */
    private static void SetParity(byte[] pData, int intLen,int pType) {

        int I;
        int J;
        int C;
        //     for (I = 0; I < intLen - 1; I++) {
        for (I = 0; I < intLen; I++) {
            if (I >= pData.length) {
                break;
            }
            pData[I] = (byte) (pData[I] & Byte.MAX_VALUE);
            J = 1;
            C = (pType & 1);
            //  while( J >= Byte.MIN_VALUE) {
            while( J <= Byte.MAX_VALUE + 1) {
                if((pData[I] & J) != 0) {
                    // ^  => VB XOR
                    C = C ^ 1;
                    Log.d(TAG, "btWB110: SetParity C 値::: = " + C);
                }
                J *= 2;
            }
            if (C == 1) {
                // |  , ビットOR
                //     pData[I] = (byte) (pData[I] | Byte.MIN_VALUE);
                pData[I] = (byte) (pData[I] | Byte.MAX_VALUE + 1);
                Log.d(TAG, "btWB110: SetParity pData[I] 値::: = " + pData[I]);
            }
        }

    }

    /**
     *
     *  '  MODULE    ：RemoveParity
     *     '  概要      ：バイト列からパリティ情報(最上位ビット)を除外する
     *     '  引数      ：変数名     属性            I/O  項目名
     *     '              pData      Byte()           I   バイト列
     *     '              intLen     Integer          I   対象の長さ
     *     '              pType      Integer          I   パリティ種類(0:Even 1:Odd)
     *     '  戻り値    ：True＝正常終了 False＝パリティエラー
     *
     */

    private static boolean RemoveParity(byte[] pData, int intLen, int pType) {

        int I;
        int J;
        int C;

        short hh = 256; // &H100

        boolean removeParity = false;
        // 'パリティチェック
        for(I = 0; I < pData.length - 1; I++) {
            if (I >= pData.length) {
                break;
            }
            J = 1;
            // & , XOR
            C = (pType & 1);
            //   while (J >= Byte.MAX_VALUE + 1) {
            while (J <= hh) {
                if ((pData[I] & J) != 0) {
                    C = C ^ 1;
                }
                J *= 2;
            }

            if (C == 1) {
                break;
            }

        } //=== END for
        for(I = 0; I < intLen - 1; I++) {
            if(I >= pData.length) {
                break;
            }
            pData[I] = (byte)(pData[I] & Byte.MAX_VALUE);
        }
        removeParity = true;
        return removeParity;
    }

    /**
     *  '  概要      ：10バイトの文字列で左詰に変換
     *     引数      ：変数名     属性            I/O  項目名
     *                 pData      String           I
     *     説明      ： "123" ---> "123_______"  _はスペースです
     *                 小数点がある場合は    "00.123" ---> "0.123_____"  _はスペースです
     *                                     ".123"   ---> "0.123_____"  _はスペースです
     *     戻り値    ：変換後の文字列
     */
    private static String BtEdit(String pData) {

        String btEdit = "";
        int i;
        int iLen;
        String sRst = "";

        // 前部のゼロをスペースに置き換え
        iLen = pData.length();
        for (i = 0; i < iLen - 1; i++) {
            sRst = pData.substring(i, i + 1);
            if (sRst.equals(".")) {
                if (i == 1) {
                    // substring(i) i から　末尾まで取得
                    sRst = "0" + pData.substring(i);
                } else {
                    sRst = pData.substring(i - 1);
                } // === END if
                break;
            }// === END if

            if (BtCInt(sRst) >= 1) {
                sRst = pData.substring(i);
                break;
            }

        } //=== END for

        sRst = sRst + "          ";
        btEdit = sRst.substring(0, 10);

        Log.d(TAG, "function BtEdit 値::: = :::btEdit " + btEdit);
        return btEdit;
    }

    /**
     *     '  MODULE    ：BtCInt
     *     '  概要      ：String型をInteger型に変換する
     *     '  説明      ：変換できない場合、初期値とする
     *     '  引数      ：変数名     属性            I/O  項目名
     *     '              pStr       String           I   対象の文字列
     *     '              pDef       Integer          I   初期値
     *     '  戻り値    ：変換結果
     */
    private static int BtCInt(String pStr, int... pDef) {

        int btCInt = 0;

        // デフォルト引数で　0 が入る
        int pDef_i = IntStream.of(pDef).sum();

        try {
            pStr = pStr.trim();
            btCInt = Integer.parseInt(pStr);
            Log.d(TAG, "btWB110: btCInt 値::: = " + btCInt);
        } catch (Exception e) {
            e.printStackTrace();

            Log.d(TAG, "btWB110: btCInt 値::: = catch 例外:::btCInt " + btCInt);
            // 0 を入れる
            btCInt = pDef_i;
        }

        return btCInt;

    }

    /***
     *     '  MODULE    ：BtCDbl
     *     '  概要      ：String型をDouble型に変換する
     *     '  説明      ：変換できない場合、初期値とする
     *     '  引数      ：変数名     属性            I/O  項目名
     *     '              pStr       String           I   対象の文字列
     *     '              pDef       Double           I   初期値
     *     '  戻り値    ：変換結果
     */
    private static double BtCDbl(String pStr, Double... pDef) {

        double btCDbl = 0;

        try {
            btCDbl = Double.parseDouble(pStr.trim());
            Log.d(TAG, "btWB110: Function 内 " + btCDbl);
        } catch (Exception e) {
            e.printStackTrace();
            btCDbl = 0;
        }

        return btCDbl;
    }

    /**
     *  DC-217A（体組成計）
     *
     */
    private void btDC217(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] byBuff;                                      // 送信バッファ
        int btRet;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        String strCommand = "";    // 送信パラメータ用
        String strResult = "";

        try {
            // 出力項目のクリア
            clearReceiveValues();
            // シリアルポート接続
            btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {
                btRet =  connection.receiveDeviceValue(status,resultSize);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "体組成計 (DC-217A): キャンセル = " + receiveRet);
                    return;
                }

                // 値取得
                strResult = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "体組成計 (DC-217)】取得データ：：： strResult = " + strResult);

                //=========================================================
                //========================= 体組成計 =======================
                //=========================================================
                if (strResult.contains("Bt")) {

                    // 「身長 取得」
                    int height_idx = strResult.indexOf("Hm");
                    String height = strResult.substring(height_idx + 3, height_idx + 3 + 5);
                    if (height.contains(",")) {
                        height = height.replace("," , "");
                    }

                    Log.d(TAG, "体組成計 (DC-217)】取得データ：：： height = " + height);

                    // 「着衣量（風袋量）」
                    int Pt_idx = strResult.indexOf("Pt");
                    String Pt_str = strResult.substring(Pt_idx + 3, Pt_idx + 3 + 5);
                    if (Pt_str.contains(",")) {
                        Pt_str = Pt_str.replace("," , "");
                    }
                    Log.d(TAG, "体組成計 (DC-217)】取得データ 「着衣量（風袋量）」：：： Pt_str = " + Pt_str);


                    // 「体重 取得」
                    int b_weight_idx = strResult.indexOf("Wk");
                    String b_weight = strResult.substring(b_weight_idx + 3, b_weight_idx + 3 + 5);
                    if (b_weight.contains(",")) {
                        b_weight = b_weight.replace("," , "");
                    }

                    Log.d(TAG, "体組成計 (DC-217)】取得データ：：： b_weight = " + b_weight);

                    // 「体脂肪率」
                    int body_fat_idx = strResult.indexOf("FW");
                    String body_fat = strResult.substring(body_fat_idx + 3, body_fat_idx + 3 + 4);
                    if (body_fat.contains(",")) {
                        body_fat = height.replace("," , "");
                    }

                    Log.d(TAG, "体組成計 (DC-217)】取得データ：：： body_fat = " + body_fat);

                    if(!height.isEmpty() || !b_weight.isEmpty() || !body_fat.isEmpty()) {

                        // 身長 値取得
                        receiveData[1] = height;
                        // 体重 値取得
                        receiveData[2] = b_weight;
                        // 体脂肪 値取得
                        receiveData[3] = body_fat;

                        // 正常終了
                        setReceiveRetWithBtRet(btRet);

                    } else {

                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-217)】 値取得失敗 :::  = ");
                        return;
                    }

                    //==============================================================
                    //========================= 身長計体重計 =========================
                    //==============================================================
                    // Bt => 体型 固定長  Hm => 身長 ,
                } else if (!strResult.contains("Bt") && strResult.contains("Hm")) {

                    // 「身長 取得」
                    int Hm_idx = strResult.indexOf("Hm");
                    String Hm_Str = strResult.substring(Hm_idx + 3, Hm_idx + 3 + 5);
                    if (Hm_Str.contains(",")) {
                        Hm_Str = Hm_Str.replace("," , "");
                    }

                    // 「着衣量（風袋量）」
                    int Pt_idx = strResult.indexOf("Pt");
                    String Pt_str = strResult.substring(Pt_idx + 3, Pt_idx + 3 + 5);
                    if (Pt_str.contains(",")) {
                        Pt_str = Pt_str.replace("," , "");
                    }
                    Log.d(TAG, "体組成計 (DC-217)】取得データ：：： 「着衣量（風袋量）」 Pt_str = " + Pt_str);

                    // 「体重 取得」
                    int b_weight_idx = strResult.indexOf("Wk");
                    String b_weight = strResult.substring(b_weight_idx + 3, b_weight_idx + 3 + 5);
                    if (b_weight.contains(",")) {
                        b_weight = b_weight.replace("," , "");
                    }

                    if(!Hm_Str.isEmpty() || !b_weight.isEmpty()) {
                        // 身長値　取得
                        receiveData[1] = Hm_Str;
                        // 体重値　取得
                        receiveData[2] = b_weight;
                        // 正常終了
                        setReceiveRetWithBtRet(btRet);

                    } else  {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-217)】 値取得失敗 :::  = ");
                        return;

                    }
                    //=========================================================
                    //========================= 体重計 =========================
                    //=========================================================
                } else if(!strResult.contains("Bt") && !strResult.contains("Hm")) {

                    // 「体重 取得」
                    int b_weight_idx = strResult.indexOf("Wk");
                    String b_weight = strResult.substring(b_weight_idx + 3, b_weight_idx + 3 + 5);
                    if (b_weight.contains(",")) {
                        b_weight = b_weight.replace("," , "");
                    }

                    // 「着衣量（風袋量）」
                    int Pt_idx = strResult.indexOf("Pt");
                    String Pt_str = strResult.substring(Pt_idx + 3, Pt_idx + 3 + 5);
                    if (Pt_str.contains(",")) {
                        Pt_str = Pt_str.replace("," , "");
                    }
                    Log.d(TAG, "体組成計 (DC-217)】取得データ：：： 「着衣量（風袋量）」 Pt_str = " + Pt_str);

                    if(!b_weight.isEmpty()) {
                        // 体重　値取得
                        receiveData[2] = b_weight;
                        // 正常終了
                        setReceiveRetWithBtRet(btRet);
                    } else {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-217)】 値取得失敗 :::  = ");
                        return;
                    }
                }
            } else {
                // ========= エラー
                receiveRet = StatusConstants.RET_ERR_MEASURE;
                return;
            }

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }

    }

    /**
     *  DC-430A（）
     *
     */
    private void btDC430(BluetoothSppConnection connection, String address) {

        final int RECEIVE_SIZE = 1000;
        byte[] byBuff;                                      // 送信バッファ
        int btRet;
        byte[] status = new byte[RECEIVE_SIZE + 1];         // 受信バッファ
        ReadResultSize resultSize = new ReadResultSize();   // 受信済みサイズ

        String strCommand = "";    // 送信パラメータ用
        String strResult = "";

        try {
            // 出力項目のクリア
            clearReceiveValues();
            // シリアルポート接続
            btRet = connection.connect(address);

            if (btRet == StatusConstants.BT_SUCCESS) {
                btRet =  connection.receiveDeviceValue(status,resultSize);

                if (resultSize.get() <= 0) {
                    // キャンセル
                    receiveRet = StatusConstants.RET_ERR_CANCEL;
                    Log.d(TAG, "体組成計 (DC-430A): キャンセル = " + receiveRet);
                    return;
                }

                // 値取得
                strResult = ByteUtil.bytesToString(status, 0, resultSize.get());
                Log.d(TAG, "体組成計 (DC-430)】取得データ：：： strResult = " + strResult);

                //=========================================================
                //========================= 体組成計 =======================
                //=========================================================
                if (strResult.contains("Bt")) {

                    // 「身長 取得」
                    int height_idx = strResult.indexOf("Hm");
                    String height = strResult.substring(height_idx + 3, height_idx + 3 + 5);
                    if (height.contains(",")) {
                        height = height.replace("," , "");
                    }

                    Log.d(TAG, "体組成計 (DC-430)】取得データ：：： height = " + height);

                    // 「着衣量（風袋量）」
                    int Pt_idx = strResult.indexOf("Pt");
                    String Pt_str = strResult.substring(Pt_idx + 3, Pt_idx + 3 + 5);
                    if (Pt_str.contains(",")) {
                        Pt_str = Pt_str.replace("," , "");
                    }
                    Log.d(TAG, "体組成計 (DC-430)】取得データ 「着衣量（風袋量）」：：： Pt_str = " + Pt_str);


                    // 「体重 取得」
                    int b_weight_idx = strResult.indexOf("Wk");
                    String b_weight = strResult.substring(b_weight_idx + 3, b_weight_idx + 3 + 5);
                    if (b_weight.contains(",")) {
                        b_weight = b_weight.replace("," , "");
                    }

                    Log.d(TAG, "体組成計 (DC-430)】取得データ：：： b_weight = " + b_weight);

                    // 「体脂肪率」
                    int body_fat_idx = strResult.indexOf("FW");
                    String body_fat = strResult.substring(body_fat_idx + 3, body_fat_idx + 3 + 4);
                    if (body_fat.contains(",")) {
                        body_fat = height.replace("," , "");
                    }

                    Log.d(TAG, "体組成計 (DC-430)】取得データ：：： body_fat = " + body_fat);

                    if(!height.isEmpty() || !b_weight.isEmpty() || !body_fat.isEmpty()) {

                        // 身長 値取得
                        //receiveData[1] = height;
                        // 体重 値取得
                        receiveData[2] = b_weight;
                        // 体脂肪 値取得
                        receiveData[3] = body_fat;

                        // 正常終了
                        setReceiveRetWithBtRet(btRet);

                    } else {

                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-430)】 値取得失敗 :::  = ");
                        return;
                    }

                    //==============================================================
                    //========================= 身長計体重計 =========================
                    //==============================================================
                    // Bt => 体型 固定長  Hm => 身長 ,
                } else if (!strResult.contains("Bt") && strResult.contains("Hm")) {

                    // 「身長 取得」
                    int Hm_idx = strResult.indexOf("Hm");
                    String Hm_Str = strResult.substring(Hm_idx + 3, Hm_idx + 3 + 5);
                    if (Hm_Str.contains(",")) {
                        Hm_Str = Hm_Str.replace("," , "");
                    }

                    // 「着衣量（風袋量）」
                    int Pt_idx = strResult.indexOf("Pt");
                    String Pt_str = strResult.substring(Pt_idx + 3, Pt_idx + 3 + 5);
                    if (Pt_str.contains(",")) {
                        Pt_str = Pt_str.replace("," , "");
                    }
                    Log.d(TAG, "体組成計 (DC-430)】取得データ：：： 「着衣量（風袋量）」 Pt_str = " + Pt_str);

                    // 「体重 取得」
                    int b_weight_idx = strResult.indexOf("Wk");
                    String b_weight = strResult.substring(b_weight_idx + 3, b_weight_idx + 3 + 5);
                    if (b_weight.contains(",")) {
                        b_weight = b_weight.replace("," , "");
                    }

                    if(!Hm_Str.isEmpty() || !b_weight.isEmpty()) {
                        // 身長値　取得
                        //receiveData[1] = Hm_Str;
                        // 体重値　取得
                        receiveData[2] = b_weight;
                        // 正常終了
                        setReceiveRetWithBtRet(btRet);

                    } else  {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-430)】 値取得失敗 :::  = ");
                        return;

                    }
                    //=========================================================
                    //========================= 体重計 =========================
                    //=========================================================
                } else if(!strResult.contains("Bt") && !strResult.contains("Hm")) {

                    // 「体重 取得」
                    int b_weight_idx = strResult.indexOf("Wk");
                    String b_weight = strResult.substring(b_weight_idx + 3, b_weight_idx + 3 + 5);
                    if (b_weight.contains(",")) {
                        b_weight = b_weight.replace("," , "");
                    }

                    // 「着衣量（風袋量）」
                    int Pt_idx = strResult.indexOf("Pt");
                    String Pt_str = strResult.substring(Pt_idx + 3, Pt_idx + 3 + 5);
                    if (Pt_str.contains(",")) {
                        Pt_str = Pt_str.replace("," , "");
                    }
                    Log.d(TAG, "体組成計 (DC-430)】取得データ：：： 「着衣量（風袋量）」 Pt_str = " + Pt_str);

                    if(!b_weight.isEmpty()) {
                        // 体重　値取得
                        receiveData[2] = b_weight;
                        // 正常終了
                        setReceiveRetWithBtRet(btRet);
                    } else {
                        // キャンセル
                        receiveRet = StatusConstants.RET_ERR_CANCEL;
                        Log.d(TAG, "体組成計 (DC-430)】 値取得失敗 :::  = ");
                        return;
                    }
                }
            } else {
                // ========= エラー
                receiveRet = StatusConstants.RET_ERR_MEASURE;
                return;
            }

        } catch (Exception e) {
            e.printStackTrace();
            receiveRet = StatusConstants.RET_ERR_MEASURE;
        }

    }

    /**
     *      MODULE    ：JCIntDef
     *      概要      ：String型をInteger型に変換する
     *      説明      ：変換できない場合、初期値とする
     *      引数      ：変数名     属性            I/O  項目名
     *                 pStr       String           I   対象の文字列
     *                 pDef       Integer          I   初期値
     *      戻り値    ：変換結果
     */
    private int JCIntDef(String pStr, int pDef) {
        try {
            int I_pStr = Integer.parseInt(pStr);
            return I_pStr;
        } catch (Exception e) {
            int I_pDef = pDef;
            return I_pDef;
        }
    }

}
