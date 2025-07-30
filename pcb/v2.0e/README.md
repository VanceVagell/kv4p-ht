# Ordering kv4p-HT v2 boards from JLCPCB

## Things you'll need:

Use Chrome.  For some reason, the JLCPCB order process is broken for me on FireFox.  Much sadness.

You'll need the following files:
* VHF:
  * <https://github.com/VanceVagell/kv4p-ht/blob/main/pcb/v2.0e/kv4p-ht/production-vhf/kv4p_HT_2.0e.zip>
  * <https://github.com/VanceVagell/kv4p-ht/blob/main/pcb/v2.0e/kv4p-ht/production-vhf/bom.csv>
  * <https://github.com/VanceVagell/kv4p-ht/blob/main/pcb/v2.0e/kv4p-ht/production-vhf/positions.csv>
* UHF:
  * <https://github.com/VanceVagell/kv4p-ht/blob/main/pcb/v2.0e/kv4p-ht/production-uhf/kv4p_HT_2.0e.zip>
  * <https://github.com/VanceVagell/kv4p-ht/blob/main/pcb/v2.0e/kv4p-ht/production-uhf/bom.csv>
  * <https://github.com/VanceVagell/kv4p-ht/blob/main/pcb/v2.0e/kv4p-ht/production-uhf/positions.csv>

## Order the PCB

* Go to <https://jlcpcb.com/>
* Click: "Add Gerber," and upload the `kv4p_HT_2.0e.zip` you downloaded before.
* JLCPCB will ask you a bunch of questions about the file.  The auto-loaded defaults are sufficient, but if you know you want to change something (solder mask color, etc) do it here.

**If all you want are the circuit boards** and you intend to **source all the parts and assemble it yourself**, then click Save To Cart here.  Check out, etc, you're done.

If you intend to use **JLCPCB to do assembly** for you (recommended), then **DO NOT (YET) CLICK Save To Cart** and proceed to the next section.

## Add PCB Assembly to the order

* Scroll to the bottom of the page, and toggle On the "PCB Assembly" option.
* I strongly recommend you click "Confirm Parts Placement: Yes".  You'll see why in a bit.  Otherwise, the defaults should be ok:
  * Economic
  * Top Side
  * 5 boards (or 2 if you want to save money on parts and only want 1 or 2 radios)
  * Tooling holes Added by JLCPCB
  * Parts selection by Customer
  * All the default "Advanced Options" are good too.
* NOW you click the big blue button, that now says "Next" instead of "Save to Cart." This enters the PCB Assembly stuff.

## Configure PCB Assembly

This next page has several tabs that you'll work your way through.

### PCB Tab

* There's not a lot here.  Feel free to look at the board and marvel at its tinyness, but really just click Next.

### Bill Of Material Tab

* Click "Add BOM File" and upload `bom.csv`
* Click "Add CPL File" and upload `positions.csv`
* Click "Process BOM & CPL".

This displays a list of parts, and quantities.  Scroll to the bottom; all the "problems" will be down there.

Things to look out for:
* "Parts Shortfall" and "Out of stock" means there aren't enough of this part in stock for your order.
  * Click on the Magnifier to search for a substitute part.  This is a complex topic that I won't try to cover here.
* Any part with a Yellow "!" is something you should look at.
  * It's known that SW3 and SW4 have !, you can ignore those (line items with the same part number.)
  * And J5 (USB-C) socket has a "special handling fee" because they're difficult to place.  This is also known.
* You can also leave a part completely unpopulated by un-checking it (far right column) if there's something you want to populate yourself for whatever reason.
* When the BOM looks good, click "Next".  If you left any parts unpopulated, it'll ask for confirmation.

### Component Placements Tab

This is the tricky part.  JLCPCB and KiCAD don't always agree on what "0 degrees" or "x=0, y=0" means for part placement.  So we need to nudge parts up/down/left/right, or rotate them, so JLCPCB knows how to assemble them correctly.

MOST passive parts don't require any adjustment.  But several ICs do, connectors typically do, and some polarized components (tantalum capacitors) do.

To use the tool on this page:

* You can zoom in and out using the scroll wheel.
* You can move the WHOLE BOARD around by right-clicking on it and dragging.
* You can select parts for adjustment by left-clicking on them, or clicking on the part in the BOM list on the right side of the screen.
* Once a component/components are selected, you can move them around using the arrow keys, and you can rotate them using the buttons along the top of the tool box.
* There are two pairs of Rotate buttons, one pair has a dot in the middle of it.  If you have multiple parts selected, use the rotate with the dot. Each part will rotate around its own axis.  The rotate without the dot will rotate everything around the group axis; almost certainly not what you want if you have multiple parts selected.
* For through-hole parts, position them by looking at the bottom of the board (Top and Bottom buttons in the upper right corner of the toolbox), and moving until the pins are lined up with the pads.  The pins will show through the board.

These are the parts that need adjustment, as of the time of this writing (2025-01-10):
* J1, the SMA.  90deg anti-clockwise.
* SW1, SW2, the PTT buttons. 180 deg, and line up pins to pads on bottom.
* J4, J6, the 2x pin headers: Rotate 90 deg.
* J5, USB-C: Line up pins and pads on bottom.
* U5, the ESP32.  90 deg clockwise, then line up pads.
* U4, USB serial chip: 90 deg clockwise. Purple dot by white arrow on board.
* C2, C16, tantalum caps: 180 deg.
* D1, the NeoPixel LED: 90 deg anti-clockwise. Purple plus by white plus on board.

Look around the board for any obviously mis-placed parts and fix them.  The black-and-white 2x2 checker board are parts that don't have 3D models.  Don't worry about these, JLCPCB will make sure they're placed right.  They also do a sanity check of the placements YOU make, and have caught mistakes I've made in the past. They're quite good.

When you're happy, click Next.

### Quote & Order tab

Here you see the costs, etc.

The only thing you need to select is "Product Description".  I usually use "Audio and Video Appliance" -> "Wireless Radio - HS Code 852990"

## Done

NOW you can click **Save To Cart**.  :-)
