## Table of Contents

- [Prerequisites](#prerequisites)
  - [Install ESP32 Drivers](#install-esp32-drivers)
- [Option 1: Arduino IDE](#option-1-arduino-ide)
  - [Install Arduino IDE](#install-arduino-ide)
  - [Install ESP32 Board Support](#install-esp32-board-support)
  - [Install Required Libraries](#install-required-libraries)
  - [Opening the Project](#opening-the-project-arduino-ide)
  - [Building the Project](#building-the-project-arduino-ide)
  - [Uploading to the ESP32](#uploading-to-the-esp32-arduino-ide)
- [Option 2: PlatformIO](#option-2-platformio)
  - [Install PlatformIO](#install-platformio)
  - [Opening the Project](#opening-the-project-platformio)
  - [Building the Project](#building-the-project-platformio)
  - [Uploading to the ESP32](#uploading-to-the-esp32-platformio)
  - [PlatformIO Specific Notes](#platformio-specific-notes)
- [Additional Notes](#additional-notes)




## Prerequisites

### Install ESP32 Drivers

1. **Download and Install the Appropriate Driver for Your System:**
   - **CP210x Drivers:** [Silicon Labs CP210x Drivers](https://www.silabs.com/developer-tools/usb-to-uart-bridge-vcp-drivers?tab=downloads)

   Ensure that the driver installation is successful:
   - **Windows:** Check the Device Manager for the COM port associated with your ESP32.
   - **macOS/Linux:** Verify the presence of `/dev/tty.*` devices corresponding to your ESP32.


## Option 1: Arduino IDE

### Install Arduino IDE

1. **Download and Install Arduino IDE:**
   - Visit the [Arduino Software Page](https://www.arduino.cc/en/software) and download the latest version suitable for your operating system.
   - Follow the installation instructions provided on the website.

### Install ESP32 Board Support

1. **Open Arduino IDE.**

2. **Install ESP32 Boards:**
   - Go to `Tools` > `Board` > `Boards Manager`.
   - In the Boards Manager window, search for **"ESP32"**.
   - Find **"esp32"** by Espressif Systems.
   - Select version **2.0.17**. The code needs to be updated to support 3.X
   - Click **Install** and Wait for the installation to complete.

3. **Configure**
   - Go to `Tools` > `Events Run On` > Select `Core 0`
      - This is done so audio processing interrupts run on a separate thread for maximum stability.

### Install Required Libraries

1. **Install EspSoftwareSerial:**
   
   - Navigate to `Sketch` > `Include Library` > `Manage Libraries`.
   - In the **Library Manager** window, enter **"EspSoftwareSerial"** into the search bar.
   - Locate the **EspSoftwareSerial** library in the search results.
   - Click the **Install** button to add the library to your Arduino environment.

2. **Install DRA818:**
   
   > **Note:** The version of the DRA818 library available through the Arduino Library Manager is currently broken. To ensure proper functionality, you need to install it manually from the official GitHub release.

   - **Download the DRA818 Library ZIP:**
     - Visit the [DRA818 v1.0.1 Release Page](https://github.com/fatpat/arduino-dra818/releases/tag/v1.0.1).
     - Click on the **"Source code (zip)"** link to download the ZIP file of the library.

   - **Add the DRA818 Library to Arduino IDE:**
     - Open the Arduino IDE.
     - Go to `Sketch` > `Include Library` > `Add .ZIP Library...`.
     - In the file dialog, navigate to the location where you downloaded the `arduino-dra818-1.0.1.zip` file.
     - Select the ZIP file and click **Open**.
     - A confirmation message should appear indicating that the library was added successfully.

3. **Confirm All Libraries Are Installed:**
   
   - After completing the above steps, ensure that both **EspSoftwareSerial** and **DRA818** are listed under `Sketch` > `Include Library`.
   - If any libraries are missing, revisit the installation steps to ensure they were added correctly.

### Opening the Project (Arduino IDE)

1. **Open the Project:**
   - Go to `File` > `Open`.
   - Navigate and open: `kv4p-ht/microcontroller-src/kv4p_ht_esp32_wroom_32/kv4p_ht_esp32_wroom_32.ino`.

### Building the Project (Arduino IDE)

1. **Select the ESP32 Board:**
   - Go to `Tools` > `Board` and select **"ESP32 Dev Module"**.

2. **Select the Correct Port:**
   - Connect your ESP32 to your computer via USB.
   - Go to `Tools` > `Port` and select the appropriate COM port (Windows) or `/dev/tty.*` device (macOS/Linux).

3. **Verify the Sketch:**
   - Click the **Verify** button (checkmark) in the Arduino toolbar or press `Ctrl+R` (`Cmd+R` on macOS).
   - The IDE will compile the sketch and display any errors or warnings in the output pane.
   - Ensure that the sketch compiles without errors.

### Uploading to the ESP32 (Arduino IDE)

1. **Upload the Firmware:**
   - Click the **Upload** button (right arrow) in the Arduino toolbar or press `Ctrl+U` (`Cmd+U` on macOS).
   - The Arduino IDE will compile (if not already done) and upload the firmware to the ESP32.
   - Monitor the output pane for upload progress and confirmation of success.


## Option 2: PlatformIO

### Install PlatformIO

1. **Download and Install VSCode:**
   - [Download VSCode](https://code.visualstudio.com/download) and follow the installation instructions for your operating system.

2. **Install PlatformIO Extension:**
   - Open VSCode.
   - Navigate to the Extensions view by clicking on the **Extensions** icon in the Activity Bar on the side of VSCode or pressing `Ctrl+Shift+X` (`Cmd+Shift+X` on macOS).
   - In the search bar, type **"PlatformIO IDE"**.
   - Locate the **PlatformIO IDE** extension by PlatformIO and click **Install**.

3. **Verify Installation:**
   - After installation, the PlatformIO icon should appear in the VSCode sidebar.
   - Click the PlatformIO icon to open the PlatformIO Home interface.

### Opening the Project (PlatformIO)

1. **Open VSCode.**
2. **Open the Project Directory:**
   - Navigate to `kv4p-ht/microcontroller-src/`.
   - Open this directory in VSCode by selecting `File` > `Open Folder` and choosing the specified path.
   - Ensure that the directory contains the `platformio.ini` file to recognize it as a PlatformIO project.
   - *Note:* Opening the root directory may prevent VSCode from recognizing it as a PlatformIO project.

### Building the Project (PlatformIO)

1. **Build the Project:**
   - Click the **Build** icon (checkmark) in the PlatformIO toolbar.
   - Alternatively, press `Ctrl+Alt+B` (`Cmd+Alt+B` on macOS).

2. **Monitor Build Process:**
   - The integrated terminal will display the compilation process.
   - Ensure there are no errors during the build.
   - A successful build will indicate that the firmware is ready for upload.

### Uploading to the ESP32 (PlatformIO)

1. **Connect Your ESP32:**
   - Use a USB cable to connect your ESP32 development board to your computer.
   - Ensure the board is recognized by your system:
     - **Windows:** Check the Device Manager for the COM port.
     - **macOS/Linux:** Check for `/dev/tty.*` devices.

2. **Select the Correct Serial Port:**
   - PlatformIO typically auto-detects the connected board.
   - If not detected, manually specify the serial port:
     - Click on the port icon in the PlatformIO toolbar.
     - Select the appropriate port from the list.

3. **Upload the Firmware:**
   - Click the **Upload** icon (right arrow) in the PlatformIO toolbar or press `Ctrl+Alt+U` (`Cmd+Alt+U` on macOS).
   - PlatformIO will compile (if not already built) and upload the firmware to the ESP32.

4. **Monitor Upload Process:**
   - Watch the terminal for messages indicating upload progress.
   - Upon successful upload, a confirmation message will appear.

### PlatformIO Specific Notes

- **Renaming the Main File for IntelliSense:**
  - **For IntelliSense to work properly in PlatformIO**, rename the main project file from `.ino` to `.cpp`. For example, rename `kv4p_ht_esp32_wroom_32.ino` to `kv4p_ht_esp32_wroom_32.cpp`.
  - **Important:** Do **not** commit this renamed file to the repository. Keeping the file as `.ino` in the repository ensures compatibility with the Arduino IDE build process.
  - **Workflow Suggestion:**
    - When working locally in PlatformIO, perform the rename to benefit from IntelliSense.
    - Before committing changes, revert the file extension back to `.ino` to maintain Arduino IDE compatibility.



## Additional Notes

- **Choosing Between Arduino IDE and PlatformIO:**
  - **PlatformIO:**
    - Offers an integrated development environment with advanced features such as dependency management, project configuration, and integrated debugging.
    - Ideal for users who require more control and flexibility over their development workflow.
  - **Arduino IDE:**
    - Provides a simpler interface that is beginner-friendly and widely used within the Arduino community.
    - Suitable for users who prefer a straightforward setup and are familiar with the Arduino ecosystem.

- **Consistent Project Structure:**
  - Ensure that any changes made in one environment (e.g., library installations, code modifications) are compatible with the other to maintain consistency across both build systems.




