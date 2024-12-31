# Firmware burning automation

Requirements:

* You can build and upload code from PlatformIO to the ESP32 boards.  Both projects:
  * `pcb/KiCAD/esp32-test-code/kv4p-ht-v2-esp32-test/`
  * `microcontroller-src/`
* I think parts of `showmeconsole.ph` require a POSIX platform, specifically the `select()` call.  Unknown what this will do on a non-POSIX platform (looking at you, Windows)

## Burning firmware

### Configure the script

* Open `pcb/KiCAD/esp32-test-code/kv4p-ht-v2-esp32-test/scripts/program.sh` in your editor of choice.
  * If you use VScode, open `pcb/KiCAD/esp32-test-code/kv4p-ht-v2-esp32-test/` as a PlatformIO project.
* Update `KV4P_ROOT` to the path of the root of the [kv4p-ht git repo](https://github.com/VanceVagell/kv4p-ht) you have checked out on your system.
* You might need to update `PIO_BIN` to the path of your `platformio` executable if it can't be found with `which platformio`.

### Run the script

```bash
cd pcb/KiCAD/esp32-test-code/kv4p-ht-v2-esp32-test/scripts/
./program.sh
```

It does everything with absolute paths, so it shouldn't matter where you are when you run it.

It will:

* Find the first instance of `/dev/ttyUSB*`.  If you don't have a board plugged in yet, it retries.
* Program the test code to the ESP32.
* Go into test mode.
    * In this mode, the computer will:
        * Output from the ESP32 is shown on the screen.
        * Pressing `r<cr>` (r and Enter) will toggle RTS
        * Pressing `d<cr>` (d and Enter) will toggle DTR
        * Pressing `q<cr>` or just `<cr>` will exit test mode.
    * While in test mode, the ESP32 will:
        * Blink the "Main" LED, 500ms on, 500ms off.
        * Cycle the NeoPixel through its colors: Red, Green, Blue, dark, dark.  900ms per state (Intentionally not sync'd with the main LED.)
        * Print an approximate voltage reading of the +5v line, every 5 seconds.
        * If an SA818 is installed, it will print the RSSI every 5 seconds.
    * Pressing the different buttons will:
        * Print on the console that a button has been pressed, and which one.
        * Left PTT will light the Red LED on the NeoPixel
        * Right PTT will light the Green LED on the NeoPixel
        * Program will light the Blue LED on the NeoPixel
    * Watch for:
        * Boot up messages.
        * If an SA818 is installed, it will perform a Handshake and Group.  You'll see whether they succeed.
        * Messages when buttons are pressed.
        * A voltage reading every 5 seconds.  It should be somewhere around 5v, give or take 0.2v  (Turns out, the ADC on the ESP32 is not very linear at all!)
        * An RSSI reading every 5 seconds.
* If any tests fail:
    * Press Ctrl-C to quit `program.sh`
    * Document the failure on a post-it and stick it to the board, put the board aside.
    * Restart `program.sh` and move on to the next board.
* Assuming all tests succeed, press `<Enter>` to proceed.
* Program the production code to the ESP32.
* Go into test mode (again).
    * The computer operates the same as the test mode above, but the ESP32 behaves differently.
    * Wait 10 seconds.
    * The ESP32 watchdog timer will expire and cause a reboot.  You'll see the failure and restart messages.
    * Yes, a "ZOMG WDT EXPIRED FAIL!" is a success criteria here.  It means the new code is programmed and running.  It won't stop rebooting until both an SA818 is connected, and the Android app is running to give it commands.
* Assuming all tests succeed, press `<Enter>` to proceed.
    * It will sleep for 15 seconds before starting again, to give you a chance to remove the programmed board and reconnect a new fresh board.
* Loop around to the beginning and do it all again.