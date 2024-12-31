#!/bin/bash


source stdbuf -o=0

# Baud rate for the test and production code
BAUD=230400

# TODO Automate the discovery of this.
KV4P_ROOT="/home/mark/src/kv4p/kv4p-ht"
KV4P_TEST_CODE=${KV4P_ROOT}/pcb/KiCAD/esp32-test-code/kv4p-ht-v2-esp32-test
KV4P_SHIP_CODE=${KV4P_ROOT}/microcontroller-src

# Override if its not in the path.
PIO_BIN=$(which platformio)

FIRST=true
while true; do
    if ! ${FIRST} ; then
        echo -n "5 seconds."
        sleep 2
        echo -n " 3..."
        sleep 1
        echo -n " 2..."
        sleep 1
        echo -n " 1..."
        sleep 1
        echo "Go."
    fi
    FIRST=false

    SERIAL_PORT=$(ls /dev/ttyUSB*)
    if [ $? != 0 ]; then
        echo "No serial port."
        continue
    fi

    echo "Found serial port ${SERIAL_PORT}. Setting paremeters"
    #stty -F ${SERIAL_PORT} ${BAUD}

    if ! pushd ${KV4P_TEST_CODE} ; then
        echo "Test code directory not found! Exiting."
        exit 1
    fi
    echo "Programming test code."
    $PIO_BIN run --target upload
    RET=$?        
    if ! popd ; then
        echo "popd failed. Not sure what's up with that."
        exit 3
    fi
    if [ $RET != 0 ]; then
        # TODO Make this red
        echo "ERROR!!!!! PlatformIO returned a failure.  Will start over. Press Enter to proceed."
        read
        continue
    fi

    echo ""
    # TODO Make this green
    echo "Succeeded."
    echo "Test the board: Push buttons, look for LEDs, etc."
    echo "Press Q<Ender> when ready to proceed, or Ctrl-C to stop."

    ./showmeconsole.py ${SERIAL_PORT} ${BAUD}

    if ! pushd ${KV4P_SHIP_CODE} ; then
        echo "Production code directory not found! Exiting."
        exit 2
    fi
    echo "Programming production code."
    $PIO_BIN run --target upload
    RET=$?
    if ! popd ; then
        echo "popd failed. Not sure what's up with that."
        exit 3
    fi
    if [ $RET != 0 ]; then
        echo "PlatformIO returned a failure.  Will start over. Press Enter to proceed."
        read
        continue
    fi

    echo ""
    echo "Done.  Check console for valid output."
    echo "Press <Enter> when ready to proceed, or Ctrl-C to stop."

    #stty -F ${SERIAL_PORT} cs8 -cstopb -parenb 230400
    #sleep 1
    #while ! read -t 0.1 foo ; do
    #    cat ${SERIAL_PORT}
    #done
    ./showmeconsole.py ${SERIAL_PORT} ${BAUD}


    echo "Pull board from USB and reconnect next board."

    sleep 10
done