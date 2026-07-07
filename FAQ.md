# Q: My radio works, but when I transmit it crashes. How do I fix this?
**A:** This is almost always because RFI is getting into the USB cable or connector. If you have a dummy load, transmit into that and if it doesn't crash you've confirmed that's the problem. Here are ways people have fixed this:

  - Try a different antenna.
  - Add a ferrite bead to the USB cable.
  - Switch to a better-shielded higher-quality USB cable.
  - Reduce tx power from "High" to "Low" in settings.
# Q: The kv4p HT Android app flashed new firmware onto my radio but it still says it needs to flash the firmware. What's wrong?
**A:** Sometimes it's as simple as closing the kv4p HT app completely, unplugging the kv4p HT radio, and plugging it back in. A full reboot of everything sometimes causes the new firmware to be detected. If that doesn't work, try installing the firmware via the web-based firmware flasher tool, this almost always works:

  - Open the [Firmware page on kv4p.com](https://kv4p.com/firmware.html) in your computer's web browser (not your phone's)
  - Plug your kv4p HT into your computer's USB port
  - Follow the steps on the page

If it doesn't detect your device while trying to flash, you may need to hold down the "Reset" button on the front of your PCB as it looks for your device. You can release the button once your device is detected and flashing begins. 
# Q: Is there an iPhone version of kv4p HT?
**A:** No, and the core contributors of this project don't currently have the skillset to build one. However, several community members have expressed interest in working on this, and have started exploring what's possible. The main sticking-point (besides iOS app programming skills) is that iPhone does not allow USB devices to just work, you have to pay an expensive licensing fee, and since this is a free open source project that's not going to happen! The people looking into this are working on getting the kv4p HT device to connect via Bluetooth instead of USB. If you're interested in helping with this, please hop into ⁠contributors and share your skills!

**Update:** Some people working on it and they have a working prototype,they're updating the [firmware to use Bluetooth instead of USB](https://github.com/VanceVagell/kv4p-ht/issues/107).
# Q: When I plug my kv4p HT into my phone, none of the lights turn on and it doesn't seem to work. The kv4p HT app asks if the radio is plugged in. What gives?
**A:** This is most common with v1.x versions of kv4p HT, since the ESP32 dev boards available on the market are often missing a couple resistors that tell Android to power it (computers ignore this and just send power). The most common solutions:

* Try plugging your USB cable or adapter the other way around. OTG cables are directional, meaning one side is for the host and one is for the device (sometimes the wire or connector will actually say this on each plug).
* You might be using a USB c to USB c cable that's not an OTG cable. Try a different cable, or buy one that's known to be an OTG cable (people in [#General on Discord](https://discord.com/channels/1290074006908829746/1290074006908829749) can recommend some).
* Upgrade to a v2.x version of the PCB, which permanently fixed this issue by not using an ESP32 dev board.
# Q: I paid money for this, and I'm frustrated it doesn't work the way I expected. What's the deal?
**A:** This Discord is for the free open source version of the project, filled with thousands of people who use or contribute to kv4p HT because of their passion for ham radio, and they want to help make something cool. If you paid money for a kit or pre-built kv4p HT, it was not from this community, it was from either a company or an individual selling kits to make it easier for people to get started. 

You should first reach out to any merchant you bought it from explaining your issue, and they might be able to help or send a replacement. If that doesn't work, the community here would be happy to help figure it out too, but please be kind and be aware we're all volunteers here in this Discord, doing this for free!
# Q: APRS doesn't decode consistently, or at all. How can I improve this?
**A:** APRS works best when you disable squelch, and turn off all 3 audio filters. This ensures that the full over-the-air audio reaches the APRS decoding logic. You can turn your phone's volume down and it will still work (you don't need to listen to the static!).
# Q: How can I improve the sensitivity of my kv4p HT? It doesn't seem as good as my other radios.
**A:** You can dramatically improve the sensitivity of your radio by adding a full-length antenna (such as a 19.5" whip like the Signal Stick linked on the [KV4P site](https://kv4p.com/)). You can improve it even more by using a 3 foot (1 meter) USB cable instead of a short cable or connector. When doubled-back on itself, a 3 foot USB cable is a nicely-tuned VHF counterpoise! Personally, if I'm out-and-about and just want basic radio usage I don't bother with a big antenna or long wire. But if I'm sitting down to play around with APRS, I usually go with both and I get a ton more decodes. 
# Q: The VHF/UHF setting in the Android app disappeared, how do I get my UHF kv4p HT radio to work again?
**A:** This happened because we changed how the Android app determines if the device is VHF or UHF. Instead of configuring it manually in settings, the manufacturer of the kit or homebrew builder of the device needs to do a one-time Non-Volatile Storage (NVS) flash to the device.

This NVS tracks not just VHF/UHF but which features are on the board, such as the presence or absense of a physical PTT button, and so we can add new things in the future without the Android app getting really complicated.

You can find and install the appropriate NVS for your device at the bottom of this page, just plug your kv4p HT into your computer and use the [web flasher](https://kv4p.com/firmware.html).
# Q: The kv4p HT app can't find my radio, and I also use the RepeaterBook app, what's wrong?
**A:** The RepeaterBook app tries to access the kv4p HT radio's serial connection to your phone, and this prevents the kv4p HT app from making a connection. You might see a dialog like the attached image, when this happens. If you see this dialog, select "Cancel", which may be enough to get your kv4p HT radio to connect properly.

If not, as a temporary workaround, you may need to uninstall the RepeaterBook app to properly use your kv4p HT. We've contacted the RepeaterBook developers to alert them to this conflict, and hope to have a fix in the future.
![RepeaterBook requesting access to KV4P HT](https://github.com/user-attachments/assets/e52d08e3-07f3-474b-941b-3c004f5cd7e9)

# Q: My kv4p HT won't flash and/or works inconsistently, what's wrong?
**A:** Some unknown manufacturers of kv4p HT modified the hardware design and their changes do not work properly. If your kv4p HT PCB looks like the photo attached here, you have one of these problematic units. The telltale signs are: a giant QR code with a thin plus-sign overlaid on it, and the kv4p HT logo is squished and surrounded by a thick white border. The official PCB design does not look like this.

Unfortunately, the best option is to build or buy a kv4p HT that uses the official design. You can always find the official design (and trusted kit manufacturers who are using it) onthe [KV4P site](https://kv4p.com/).

![A problematic PCB](https://github.com/user-attachments/assets/db7b11bf-ea3c-4f4e-a0d0-2a5546e0e989)


# Q: I think I have a dual band kv4p HT, why can I only use VHF or UHF and not both?
**A:** There are no dual band kv4p HTs, they are all either VHF or UHF but never both. 

Some vendors who sell the kits or pre-made units have misleading titles for their products, such as "kv4p HT VHF/UHF", which they intend to mean "you can choose either VHF or UHF when you buy it" but many people take to mean "dual band". If you don't notice the drop-down to choose the band you want, it's easy to make this mistake when ordering.

Confirm whether you have a VHF or UHF unit from the receipt of where you bought it, and ensure you have the appropriate non-volatile storage (NVS) flashed onto it, using the [web flasher](https://kv4p.com/firmware.html), so that your radio shows up on the proper band in the kv4p HT app. If you attempt to transmit on the incorrect band, you may damage your radio.
