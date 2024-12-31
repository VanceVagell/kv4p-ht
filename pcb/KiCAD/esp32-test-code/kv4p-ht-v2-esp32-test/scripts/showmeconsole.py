#!/usr/bin/python

from time import sleep
from select import select
from sys import argv, stdin
import readchar
import serial

default_baud_rate = 460800

def usage():
    print (f"Usage: {argv[0]} SERIAL_PORT [BAUD_RATE]")
    print("   SERIAL_PORT is required, BAUD_RATE is optional (defaults to {default_baud_rate})")
    exit(1)
try:
    serial_port = argv[1]
except IndexError:
    usage()

try:
    baud_rate = argv[2]
except IndexError:
    baud_rate = default_baud_rate

ser = None
prev_cts = None
prev_cd = None
prev_dsr = None
prev_rts = None
prev_dtr = None
prev_c = None

while True:
    try:
        if ser is None:
            print(f"Trying {serial_port}")
            ser = serial.Serial(serial_port, baud_rate)
            print("Got Serial.")

        cts = ser.cts
        if cts != prev_cts:
            print(f"CTS: {cts}")
        prev_cts = cts

        cd = ser.cd
        if cd != prev_cd:
            print(f"DCD: {cd}")
        prev_cd = cd

        dsr = ser.dsr
        if dsr != prev_dsr:
            print(f"DSR: {dsr}")
        prev_dsr = dsr

        rts = ser.rts
        if rts != prev_rts:
            print(f"RTS: {rts}")
        prev_rts = rts

        dtr = ser.dtr
        if dtr != prev_dtr:
            print(f"DTR: {dtr}")
        prev_dtr = dtr

        while ser.in_waiting > 0:
            ch = ser.read(1)
            try:
                c = ch.decode(stdin.encoding)
            except UnicodeDecodeError:
                c = ch   # just print the byte 

            print(c, end="")

        # RTS=True, DTR=False, holds the ESP32 in reset.
        # NOTE: This select() trick only works on POSIX systems.
        if select([stdin], [], [], 0)[0]:
            # Theres at least one character waiting on stdin
            c = stdin.read(1)
            if c == 'r':
                ser.rts = not rts
            elif c == 'd':
                ser.dtr = not dtr
            elif c == '\n' and (prev_c == '\n' or prev_c == 'q' or prev_c == None):
                print("Exiting...")
                exit(0)
            prev_c = c

    except OSError:
        ser = None
        print("Can't open serial...")
        sleep(1)
