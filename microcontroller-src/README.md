## Table of Contents

- [Prerequisites](#prerequisites)
  - [Install PlatformIO](#install-platformio)
  - [Install ESP32 Drivers](#install-esp32-drivers)
- [Opening the Project](#opening-the-project)
- [Building the Project](#building-the-project)
- [Uploading to the ESP32](#uploading-to-the-esp32)



## Prerequisites

### - Install PlatformIO

1. **Download and Install VSCode:**
   - [Download VSCode](https://code.visualstudio.com/download) and follow the installation instructions for your operating system.

2. **Install PlatformIO Extension:**
   - Open VSCode.
   - Go to the Extensions view by clicking on the Extensions icon in the Activity Bar on the side of VSCode or pressing `Ctrl+Shift+X` (`Cmd+Shift+X` on macOS).
   - Search for **"PlatformIO IDE"**.
   - Click **Install** on the PlatformIO IDE extension by PlatformIO.

3. **Verify Installation:**
   - After installation, you should see the PlatformIO icon in the VSCode sidebar. Click it to open the PlatformIO Home.

### - Install ESP32 Drivers
1. **Download and install the appropriate driver for your system:**
   - CP210x Drivers: [Silicon Labs CP210x Drivers](https://www.silabs.com/developer-tools/usb-to-uart-bridge-vcp-drivers?tab=downloads)


## Opening the project

In VSCode, open the kv4p-ht/microcontroller-src/kv4p_ht_esp32_wroom_32 directory that contains platformio.ini. If the root directory is opened, VSCode may not recognize it as a PlatformIO project.


## Building the Project

You can now build the project to compile the firmware.

1. **Build the Project:**
   - Click the **Build** icon (checkmark) in the PlatformIO toolbar.
   - Alternatively, press `Ctrl+Alt+B` (`Cmd+Alt+B` on macOS).

2. **Monitor Build Process:**
   - The terminal will display the compilation process. Ensure there are no errors.
   - Successful build output will indicate that the firmware is ready to be uploaded.



## Uploading to the ESP32

With the project built successfully, you can now upload the firmware to your ESP32 board.

1. **Connect Your ESP32:**
   - Use a USB cable to connect your ESP32 development board to your computer.
   - Ensure the board is recognized by your system. On Windows, check the Device Manager for the COM port; on macOS/Linux, check `/dev/tty.*` devices.

2. **Select the Correct Serial Port:**
   - PlatformIO usually auto-detects the connected board. If not, you may need to specify the serial port.
   - You can select the port by clicking on the port icon in the PlatformIO toolbar if needed.

3. **Upload the Firmware:**
   - Click the **Upload** icon (right arrow) in the PlatformIO toolbar or press `Ctrl+Alt+U` (`Cmd+Alt+U` on macOS).
   - PlatformIO will compile (if not already built) and upload the firmware to the ESP32.

4. **Monitor Upload Process:**
   - Watch the terminal for messages indicating the upload progress.
   - Upon successful upload, you should see a confirmation message.