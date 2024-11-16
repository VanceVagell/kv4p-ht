package com.vagell.kv4pht.firmware.bearconsole;
/**
 *   @description: This is used to flash the ESP32 altimeter firmware from the Android device using an OTG cable
 *   so that the store Android application is compatible with the altimeter.
 *   Note that this is an Android port of the ESPLoader.py done by myself
 *   as I could not find anything on the internet!!!
 *   It uses the Physicaloid library but should be easy to use with any other USB library
 *   It could also be written in pure Java so that it could be integrated with the Arduino env
 *   This has been only tested with ESP32 chips. Feel free to re-use it for your own project
 *   Please make sure that you do report any bugs so that I can fix them
 *   So far it is reliable enough for me
 *
 *   @author: boris.dureau@neuf.fr
 *
 *  Note: the flashing is very slow due to the delay en the send command. This needs to be improved
 *  Also the reset() and enterBootLoader() do not currently work, you will need to enter the bootloader mode using the reset
 *  and boot buttons on the board
 **/

import android.content.Context;
import android.util.Base64;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.vagell.kv4pht.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Deflater;



public class CommandInterfaceESP32 {
    public static int ESP_FLASH_BLOCK = 0x400;

    private static final int ESP_ROM_BAUD = 115200;
    private static final int FLASH_WRITE_SIZE = 0x400;
    private static final int STUBLOADER_FLASH_WRITE_SIZE = 0x8000;
    private static final int FLASH_SECTOR_SIZE = 0x1000; // Flash sector size, minimum unit of erase.
    private static final int FLASH_TIMEOUT_MS = 4000;
    private static final int COMMAND_TIMEOUT_MS = 100;

    private static final int CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000;
    private static final int ESP8266 = 0x8266;
    public static final int ESP32 = 0x32;
    private static final int ESP32S2 = 0x3252;
    private static final int ESP32_DATAREGVALUE = 0x15122500;
    private static final int ESP8266_DATAREGVALUE = 0x00062000;
    private static final int ESP32S2_DATAREGVALUE = 0x500;

    private static final int BOOTLOADER_FLASH_OFFSET = 0x1000;
    private static final int ESP_IMAGE_MAGIC = 0xe9;

    // Commands supported by ESP8266 ROM bootloader
    private static final int ESP_FLASH_BEGIN = 0x02;
    private static final int ESP_FLASH_DATA = 0x03;
    private static final int ESP_FLASH_END = 0x04;
    private static final int ESP_MEM_BEGIN = 0x05;
    private static final int ESP_MEM_END = 0x06;
    private static final int ESP_MEM_DATA = 0x07;
    private static final int ESP_SYNC = 0x08;
    private static final int ESP_WRITE_REG = 0x09;
    private static final int ESP_READ_REG = 0x0A;

    // Some comands supported by ESP32 ROM bootloader (or -8266 w/ stub)
    private static final int ESP_SPI_SET_PARAMS = 0x0B; // 11
    private static final int ESP_SPI_ATTACH = 0x0D; // 13
    private static final int ESP_READ_FLASH_SLOW = 0x0E; // 14 // ROM only, much slower than the stub flash read
    private static final int ESP_CHANGE_BAUDRATE = 0x0F; // 15
    private static final int ESP_FLASH_DEFL_BEGIN = 0x10; // 16
    private static final int ESP_FLASH_DEFL_DATA = 0x11; // 17
    private static final int ESP_FLASH_DEFL_END = 0x12; // 18
    private static final int ESP_SPI_FLASH_MD5 = 0x13; // 19

    // Commands supported by ESP32-S2/S3/C3/C6 ROM bootloader only
    private static final int ESP_GET_SECURITY_INFO = 0x14;

    // Some commands supported by stub only
    private static final int ESP_ERASE_FLASH = 0xD0;
    private static final int ESP_ERASE_REGION = 0xD1;
    private static final int ESP_READ_FLASH = 0xD2;
    private static final int ESP_RUN_USER_CODE = 0xD3;

    // Response code(s) sent by ROM
    private static final int ROM_INVALID_RECV_MSG = 0x05;

    // Initial state for the checksum routine
    private static final byte ESP_CHECKSUM_MAGIC = (byte) 0xEF;

    private static final int UART_DATE_REG_ADDR = 0x60000078;

    private static final int USB_RAM_BLOCK = 0x800;
    private static final int ESP_RAM_BLOCK = 0x1800;

    // Timeouts
    private static final int DEFAULT_TIMEOUT = 3000;
    private static final int CHIP_ERASE_TIMEOUT = 120000; // timeout for full chip erase in ms
    private static final int MAX_TIMEOUT = CHIP_ERASE_TIMEOUT * 2; // longest any command can run in ms
    private static final int SYNC_TIMEOUT = 100; // timeout for syncing with bootloader in ms
    private static final int ERASE_REGION_TIMEOUT_PER_MB = 30000; // timeout (per megabyte) for erasing a region in ms
    private static final int MEM_END_ROM_TIMEOUT = 500;
    private static final int MD5_TIMEOUT_PER_MB = 8000;

    private static boolean IS_STUB = false;

    static class cmdRet {
        int retCode;
        byte retValue[] = new byte[512];

    }
    UploadSTM32CallBack mUpCallback;
    UsbSerialPort mSerialPort;
    Context mContext;

    public CommandInterfaceESP32(Context context, UploadSTM32CallBack UpCallback, UsbSerialPort serialPort) {
        mUpCallback = UpCallback;
        mSerialPort = serialPort;
        mContext = context;
    }

    public boolean initChip() {
        if (null == mSerialPort) {
            return false;
        }

        // Restart the ESP32 into boot loader mode, and clear out any stray serial data.
        // enterBootLoader();

        try {
            mSerialPort.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        boolean syncSuccess = false;

        mUpCallback.onInfo("Syncing with ESP32"  + "\n");
        // first do the sync
        for (int i = 0; i < 3; i++) {
            mUpCallback.onInfo("Sync attempt:"  + (i+1)+ "\n");
            if (sync() == 0) {

            } else {
                syncSuccess = true;
                mUpCallback.onInfo("Sync success"  + "\n");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {

                }
                break;
            }
        }
        return syncSuccess;
    }

    public void loadStubFromFile() {
        try {
            // Read the JSON file from assets
            InputStream is = mContext.getResources().openRawResource(R.raw.esp32_stub_loader);

            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();

            String jsonStr = new String(buffer, "UTF-8");

            // Parse the JSON
            JSONObject json = new JSONObject(jsonStr);

            // Extract the fields
            int entry = json.getInt("entry");
            String textBase64 = json.getString("text");
            int textStart = json.getInt("text_start");
            String dataBase64 = json.getString("data");
            int dataStart = json.getInt("data_start");

            // Decode the base64 strings into byte arrays
            byte[] textSegment = Base64.decode(textBase64, Base64.DEFAULT);
            byte[] dataSegment = Base64.decode(dataBase64, Base64.DEFAULT);

            // Now use these in the loadStub() method
            loadStub(textSegment, textStart, dataSegment, dataStart, entry);

        } catch (IOException e) {
            mUpCallback.onInfo("IOException: " + e.getMessage() + "\n");
        } catch (JSONException e) {
            mUpCallback.onInfo("JSONException: " + e.getMessage() + "\n");
        }
    }

    public void loadStub(byte[] textSegment, int textStart, byte[] dataSegment, int dataStart, int entry) {
        mUpCallback.onInfo("Uploading stub loader..." + "\n");

        // Load text segment
        loadSegment(textSegment, textStart);

        // Load data segment
        loadSegment(dataSegment, dataStart);

        // Start the stub loader
        startStub(entry);

        IS_STUB = true;
        mUpCallback.onInfo("Stub loader uploaded." + "\n");
    }

    private void loadSegment(byte[] segment, int address) {
        int length = segment.length;
        int numBlocks = (length + ESP_RAM_BLOCK - 1) / ESP_RAM_BLOCK;
        int eraseSize = (length + 3) & ~3;

        // Send MEM_BEGIN command
        byte[] pkt = _appendArray(_int_to_bytearray(eraseSize),
                _int_to_bytearray(numBlocks));
        pkt = _appendArray(pkt, _int_to_bytearray(ESP_RAM_BLOCK));
        pkt = _appendArray(pkt, _int_to_bytearray(address));
        sendCommand((byte) ESP_MEM_BEGIN, pkt, 0, DEFAULT_TIMEOUT);

        // Send MEM_DATA commands
        int seq = 0;
        int position = 0;
        while (position < length) {
            int blockSize = Math.min(length - position, ESP_RAM_BLOCK);
            byte[] block = _subArray(segment, position, blockSize);

            byte[] pkt2 = _appendArray(_int_to_bytearray(blockSize),
                    _int_to_bytearray(seq));
            pkt2 = _appendArray(pkt2, _int_to_bytearray(0));
            pkt2 = _appendArray(pkt2, _int_to_bytearray(0));
            pkt2 = _appendArray(pkt2, block);

            sendCommand((byte) ESP_MEM_DATA, pkt2, _checksum(block), DEFAULT_TIMEOUT);

            seq++;
            position += blockSize;
        }
    }

    private void startStub(int entryAddress) {
        // Send MEM_END command with entry point
        byte[] pkt = _appendArray(_int_to_bytearray(entryAddress),
                _int_to_bytearray(0));
        sendCommand((byte) ESP_MEM_END, pkt, 0, DEFAULT_TIMEOUT);
    }

    public void changeBaudRate() {
        byte pkt[] = _appendArray(_int_to_bytearray(921600),_int_to_bytearray(0));
        sendCommand((byte) ESP_CHANGE_BAUDRATE, pkt, 0, COMMAND_TIMEOUT_MS);

        // second we change the comport baud rate
        mUpCallback.onInfo("Changing baud rate to 921600"  + "\n");
        try {
            mSerialPort.setParameters(921600, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // let's wait
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
        }

        // flush anything on the port
        drain();
    }

    private void drain() {
        byte[] buf = new byte[1];
        while (true) {
            try {
                int bytesRead = mSerialPort.read(buf, 1000);
                if (bytesRead == 0) {
                    break;
                }
            } catch (IOException e) {
                break;
            }
        }
    }

    /*
     * This will initialise the chip
     */
    public int sync() {
        int x;
        int response = 0;
        byte cmddata[] = new byte[36];

        cmddata[0] = (byte) (0x07);
        cmddata[1] = (byte) (0x07);
        cmddata[2] = (byte) (0x12);
        cmddata[3] = (byte) (0x20);
        for (x = 4; x < 36; x++) {
            cmddata[x] = (byte) (0x55);
        }

        for (x = 0; x < 7; x++) {
            cmdRet ret = sendCommand((byte) ESP_SYNC, cmddata, 0, COMMAND_TIMEOUT_MS);
            if (ret.retCode == 1) {
                response = 1;
                break;
            }
        }
        return response;
    }

    /*
     * This will send a command to the chip
     */
    public cmdRet sendCommand(byte opcode, byte buffer[], int chk, int timeout) {

        cmdRet retVal = new cmdRet();
        int i = 0;
        byte data[] = new byte[8 + buffer.length];
        data[0] = 0x00;
        data[1] = opcode;
        data[2] = (byte) ((buffer.length) & 0xFF);
        data[3] = (byte) ((buffer.length >> 8) & 0xFF);
        data[4] = (byte) ((chk & 0xFF));
        data[5] = (byte) ((chk >> 8) & 0xFF);
        data[6] = (byte) ((chk >> 16) & 0xFF);
        data[7] = (byte) ((chk >> 24) & 0xFF);

        for (i = 0; i < buffer.length; i++) {
            data[8 + i] = buffer[i];
        }

        // System.out.println("opcode:"+opcode);
        int ret = 0;
        retVal.retCode = 0;
        byte buf[] = slipEncode(data);

        try {
            mSerialPort.write(buf, buf.length, timeout);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try { Thread.sleep(5); } catch (InterruptedException e) {}
        
        int numRead = 0;
        
        for (i = 0; i < 10; i++) {
            numRead = recv(retVal.retValue, retVal.retValue.length, timeout/5);
            // mUpCallback.onInfo("num read:" + numRead);
            if (numRead == 0) {
                retVal.retCode = -1;
                continue;
            } else if (numRead == -1) {
                retVal.retCode = -1;
                continue;
            }

            if (retVal.retValue[0] != (byte) 0xC0) {
                //mUpCallback.onInfo("invalid packet\n");
                // System.out.println("Packet: " + printHex(retVal.retValue));
                retVal.retCode = -1;
                continue;
            }

            if (retVal.retValue[0] == (byte) 0xC0) {
                mUpCallback.onInfo("Correct return value from ESP32\n");
                // System.out.println("Packet: " + printHex(retVal.retValue));
                retVal.retCode = 1;
                break;
            }
        }

        return retVal;
    }
    private int recv(byte[] buf, int length, long timeout) {
        int retval = 0;
        int totalRetval = 0;
        long endTime;
        long startTime = System.currentTimeMillis();
        byte[] tmpbuf = new byte[length];

        while (true) {
            try {
                retval = mSerialPort.read(tmpbuf, length, 1000);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (retval > 0) {
                System.arraycopy(tmpbuf, 0, buf, totalRetval, retval);
                totalRetval += retval;
                startTime = System.currentTimeMillis();
                //if (DEBUG_SHOW_RECV) {
                //    Log.d(TAG, "recv(" + retval + ") : " + toHexStr(buf, totalRetval));
                //}
            }
            
            if (totalRetval >= 8) { 
                break;
            }

            endTime = System.currentTimeMillis();
            if ((endTime - startTime) > timeout) {
                //Log.e(TAG, "recv timeout.");
                break;
            }
        }
        return retval;
    }
    /*private int _wait_for_ack( long timeout, byte readVal[]) {
        long stop = System.currentTimeMillis() + timeout;
        byte got[] =new byte[1];
        while (mPhysicaloid.read(got) <1) {
            if (System.currentTimeMillis() > stop)
                break;
        }
        if (got[0] == (byte) 0xC0) {
            mUpCallback.onInfo("We have a start byte\n");
            byte readByte[] = new byte[1];
            readByte[0] = got[0];
            while(mPhysicaloid.read(got,1)>0) {
                mUpCallback.onInfo("We have another one\n");
                readByte =_appendArray(readByte,got);
                if (System.currentTimeMillis() > stop)
                    break;
            }
            mUpCallback.onInfo(readByte.toString());
            readVal = readByte;
            return readVal.length;
        }
        else {
            //tvAppend(tvRead,info + " Not 0x79");
            //mUpCallback.onInfo(info+ "This is Not 0x79");
            mUpCallback.onInfo("not receiving!!!\n");
            return 0;
        }
    }*/
    /*
     * This will do a SLIP encode
     */
    public byte[] slipEncode(byte buffer[]) {
        byte encoded[] = new byte[] {(byte) (0xC0)};

        for (int x = 0; x < buffer.length; x++) {
            if (buffer[x] == (byte) (0xC0)) {
                encoded = _appendArray(encoded, new byte[] {(byte) (0xDB)});
                encoded = _appendArray(encoded, new byte[] {(byte) (0xDC)});

            } else if (buffer[x] == (byte) (0xDB)) {
                encoded = _appendArray(encoded, new byte[] {(byte) (0xDB)});
                encoded = _appendArray(encoded, new byte[] {(byte) (0xDD)});
            } else {
                encoded = _appendArray(encoded,new byte[] {buffer[x]});
            }
        }
        encoded = _appendArray(encoded,new byte[] {(byte) (0xC0)});

        return encoded;
    }

    /*
     * This does a reset in order to run the prog after flash
     * does not currently work on Android
     */
    public void reset() {
        // byte pkt[] = _int_to_bytearray(1 ); // 1 means "reboot afterwards"
        // sendCommand((byte) ESP_FLASH_DEFL_END, pkt, 0, COMMAND_TIMEOUT_MS);

        try {
            mSerialPort.setDTR(false);
            mSerialPort.setRTS(true);
            Thread.sleep(100);
            mSerialPort.setDTR(true);
            mSerialPort.setRTS(false);
            Thread.sleep(50);
            mSerialPort.setDTR(false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /*
     * enter bootloader mode
     * does not currently work on Android
     */
    public void enterBootLoader() {
        try {
            // Step 1: Pull IO0 low to enter bootloader mode
            mSerialPort.setDTR(true); // DTR high -> IO0 low (due to inversion)

            // Step 2: Assert reset (pull EN low)
            mSerialPort.setRTS(true); // RTS high -> EN low (due to inversion)

            // Step 3: Wait for at least 100 milliseconds
            Thread.sleep(100);

            // Step 4: Release reset (pull EN high)
            mSerialPort.setRTS(false); // RTS low -> EN high

            // Step 5: Wait for the chip to boot (e.g., 50 milliseconds)
            Thread.sleep(50);

            // Step 6: Release IO0 to complete bootloader entry
            mSerialPort.setDTR(false); // DTR low -> IO0 high
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @name flash_defl_block Send one compressed block of data to program into SPI Flash memory
     */

    public void flash_defl_block(byte data[], int seq, int timeout) {

        byte pkt[] = _appendArray(_int_to_bytearray(data.length),_int_to_bytearray(seq));
        pkt = _appendArray(pkt,_int_to_bytearray(0));
        pkt = _appendArray(pkt,_int_to_bytearray(0));
        pkt = _appendArray(pkt, data);

        sendCommand((byte) ESP_FLASH_DEFL_DATA, pkt, _checksum(data), timeout);

    }

    public void init() {

        int _flashsize = 4 * 1024 * 1024;

        if (!IS_STUB) {
            //System.out.println("No stub...");
            byte pkt[] = _appendArray(_int_to_bytearray(0),_int_to_bytearray(0));
            sendCommand((byte) ESP_SPI_ATTACH, pkt, 0, COMMAND_TIMEOUT_MS);
        }

        // We are hardcoding 4MB flash for an ESP32
        //System.out.println("Configuring flash size...");
        mUpCallback.onInfo("Configuring flash size..."  + "\n");

        byte pkt2[] = _appendArray(_int_to_bytearray(0),_int_to_bytearray(_flashsize));
        pkt2 = _appendArray(pkt2,_int_to_bytearray(0x10000));
        pkt2 = _appendArray(pkt2,_int_to_bytearray(4096));
        pkt2 = _appendArray(pkt2,_int_to_bytearray(256));
        pkt2 = _appendArray(pkt2,_int_to_bytearray(0xFFFF));

        sendCommand((byte) ESP_SPI_SET_PARAMS, pkt2, 0, COMMAND_TIMEOUT_MS);

    }


    /**
     * @name flashData Program a full, uncompressed binary file into SPI Flash at a
     *       given offset. If an ESP32 and md5 string is passed in, will also verify
     *       memory. ESP8266 does not have checksum memory verification in ROM
     */
    public void flashData(byte binaryData[], int offset, int part) {
        int filesize = binaryData.length;

        mUpCallback.onInfo("Writing data with filesize: " + filesize);

        byte image[] = compressBytes(binaryData);
        int blocks = flash_defl_begin(filesize, image.length, offset);

        int seq = 0;
        int written = 0;
        int address = offset;
        int position = 0;

        long t1 = System.currentTimeMillis();

        while (image.length - position > 0) {

            double percentage = Math.floor((double)(100 * (seq + 1)) / (double)blocks);

            mUpCallback.onInfo("percentage: " + percentage + "\n");
            mUpCallback.onUploading( (int) percentage);

            byte block[];

            if (image.length - position >= (IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE)) {
                block = _subArray(image, position, FLASH_WRITE_SIZE);
            } else {
                // Pad the last block
                block = _subArray(image, position, image.length - position);

                // we have an incomplete block (ie: less than 1024) so let pad the missing block
                // with 0xFF
                /*byte tempArray[] = new byte[(IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE) - block.length];
                for (int i = 0; i < tempArray.length; i++) {
                    tempArray[i] = (byte) 0xFF;
                }
                block = _appendArray(block, tempArray);*/
            }

            flash_defl_block(block, seq, FLASH_TIMEOUT_MS);
            seq += 1;
            written += block.length;
            position += (IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE);
        }

        long t2 = System.currentTimeMillis();
        mUpCallback.onInfo("Took " + (t2 - t1) + "ms to write " + filesize + " bytes" + "\n");
    }

    private int flash_defl_begin(int size, int compsize, int offset) {

        int num_blocks = (int) Math.floor((double) (compsize + (IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE) - 1) / (double) (IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE));
        int erase_blocks = (int) Math.floor((double) (size + (IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE) - 1) / (double) (IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE));
        // Start time
        long t1 = System.currentTimeMillis();

        int write_size, timeout;
        if (IS_STUB) {
            //using a stub (will use it in the future)
            write_size = size;
            timeout = 3000;
        } else {
            write_size = erase_blocks * (IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE);
            timeout = timeout_per_mb(ERASE_REGION_TIMEOUT_PER_MB, write_size);
        }

        mUpCallback.onInfo("Compressed " + size + " bytes to " + compsize + "..."+ "\n");

        byte pkt[] = _appendArray(_int_to_bytearray(write_size), _int_to_bytearray(num_blocks));
        pkt = _appendArray(pkt, _int_to_bytearray((IS_STUB ? STUBLOADER_FLASH_WRITE_SIZE : FLASH_WRITE_SIZE)));
        pkt = _appendArray(pkt, _int_to_bytearray(offset));

        // System.out.println("params:" +printHex(pkt));
        sendCommand((byte) ESP_FLASH_DEFL_BEGIN, pkt, 0, timeout);

        // end time
        long t2 = System.currentTimeMillis();
        if (size != 0 && IS_STUB == false) {
            System.out.println("Took " + ((t2 - t1) / 1000) + "." + ((t2 - t1) % 1000) + "s to erase flash block");
            mUpCallback.onInfo("Took " + ((t2 - t1) / 1000) + "." + ((t2 - t1) % 1000) + "s to erase flash block\n");
        }
        return num_blocks;
    }

    /*
     * Send a command to the chip to find out what type it is
     */
    public int detectChip() {
        int chipMagicValue = readRegister(CHIP_DETECT_MAGIC_REG_ADDR);

        int ret = 0;
        if (chipMagicValue == 0xfff0c101)
            ret = ESP8266;
        if (chipMagicValue == 0x00f01d83 || chipMagicValue == 0xf01d)
            ret = ESP32;
        if (chipMagicValue == 0x000007c6)
            ret = ESP32S2;

        return ret;
    }
    ////////////////////////////////////////////////
    // Some utility functions
    ////////////////////////////////////////////////

    /*
     * Just usefull for debuging to check what I am sending or receiving
     */
    public String printHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ ");
        for (byte b : bytes) {
            sb.append(String.format("0x%02X ", b));
        }
        sb.append("]");
        return sb.toString();
    }

    /*
     * This takes 2 arrays as params and return a concatenate array
     */
    private byte[] _appendArray(byte arr1[], byte arr2[]) {

        byte c[] = new byte[arr1.length + arr2.length];

        for (int i = 0; i < arr1.length; i++) {
            c[i] = arr1[i];
        }
        for (int j = 0; j < arr2.length; j++) {
            c[arr1.length + j] = arr2[j];
        }
        return c;
    }

    /*
     * get part of an array
     */
    private byte[] _subArray(byte arr1[], int pos, int length) {

        byte c[] = new byte[length];

        for (int i = 0; i < (length); i++) {
            c[i] = arr1[i + pos];
        }
        return c;
    }

    /*
     * Calculate the checksum.
     */
    public int _checksum(byte[] data) {
        int chk = ESP_CHECKSUM_MAGIC;
        int x = 0;
        for (x = 0; x < data.length; x++) {
            chk ^= data[x];
        }
        return chk;
    }

    public int read_reg(int addr, int timeout) {
        cmdRet val;
        byte pkt[] = _int_to_bytearray(addr);
        val = sendCommand((byte)ESP_READ_REG, pkt, 0, timeout);
        return val.retValue[0];
    }

    /**
     * @name readRegister Read a register within the ESP chip RAM, returns a
     *       4-element list
     */
    public int readRegister(int reg) {

        long retVals[] = { 0 };
        //int retVals = 0;
        cmdRet ret;

        Struct struct = new Struct();

        try {

            byte packet[] = _int_to_bytearray(reg);

            ret = sendCommand((byte) ESP_READ_REG, packet, 0, 100);
            Struct myRet = new Struct();

            byte subArray[] = new byte[4];
            subArray[0] = ret.retValue[5];
            subArray[1] = ret.retValue[6];
            subArray[2] = ret.retValue[7];
            subArray[3] = ret.retValue[8];

            retVals = myRet.unpack("I", subArray);
            //retVals =_bytearray_to_int(ret.retValue[5], ret.retValue[6], ret.retValue[7], ret.retValue[8]);

            //System.out.println(	"retVals:"+retVals);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return (int) retVals[0];
    }



    /**
     * @name timeoutPerMb Scales timeouts which are size-specific
     */
    private  int timeout_per_mb(int seconds_per_mb, int size_bytes) {
        int result = seconds_per_mb * (size_bytes / 1000000);
        if (result < 3000) {
            return 3000;
        } else {
            return result;
        }
    }

    private byte[] _int_to_bytearray(int i) {
        byte ret[] = { (byte) (i & 0xff), (byte) ((i >> 8) & 0xff), (byte) ((i >> 16) & 0xff),
                (byte) ((i >> 24) & 0xff) };
        return ret;
    }

    private int _bytearray_to_int(byte i, byte j, byte k, byte l) {
        return ((int)i | (int)(j << 8) | (int)(k << 16) | (int)(l << 24));
    }



    /**
     * Compress a byte array using ZLIB compression
     *
     * @param uncompressedData byte array of uncompressed data
     * @return byte array of compressed data
     */
    public byte[] compressBytes(byte[] uncompressedData) {
        // Create the compressor with highest level of compression
        Deflater compressor = new Deflater();
        compressor.setLevel(Deflater.BEST_COMPRESSION);

        // Give the compressor the data to compress
        compressor.setInput(uncompressedData);
        compressor.finish();

        // Create an expandable byte array to hold the compressed data.
        // You cannot use an array that's the same size as the orginal because
        // there is no guarantee that the compressed data will be smaller than
        // the uncompressed data.
        ByteArrayOutputStream bos = new ByteArrayOutputStream(uncompressedData.length);

        // Compress the data
        byte[] buf = new byte[1024];
        while (!compressor.finished()) {
            int count = compressor.deflate(buf);
            bos.write(buf, 0, count);
        }
        try {
            bos.close();
        } catch (IOException e) {
        }

        // Get the compressed data
        byte[] compressedData = bos.toByteArray();
        return compressedData;
    }
}
