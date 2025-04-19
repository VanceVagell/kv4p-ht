# Things to fix

v2.1a was the first version made for the metal case.  I removed some hardware, a bit too much.

## Metal Case Only:
* **DONE** Add pull-ups on the PTT switch pins, even though we don't have switches.  Means the software won't get errant PTT signals if it doesn't enable internal pull-ups.
  * I put buttons on them anyway, since we have the space on the board.  Might as well, it's all cheap parts.

* Move the SMA closer to the edge of the board.  Not enough of it sticks out the panel.

## Both Boards:
* The NeoPixel isn't lighting up, don't know why.  Yet.  Working on it.
  * **DONE** Metal Case
  * TODO Main board
  * R4, 100R, combined with C13, 2.2nF, probably rolled off the data.  Replacing R4 with 0R (like in HaliKey v2) makes it work again.
  * -3dB at 723kHz.  Yeah, that'll make a 400kHz square wave VERY round.  And an 800kHz basically gone.  It originally used 800kHz, but I dropped that to 400kHz to see if it worked.  It didn't.
  * Since removing both works in HaliKey v2, I'll do that.  Remove both R4 and C13.
  * Shoot.  The main board is this same way.  The LED on that one must be more tolerant.  I should probably fix those too.

* Put a CMCC on the USB line.  Reports from people that poorly shielded USB cables are getting RF into them and causing USB disconnects on transmit.
  * **DONE** Metal Case
  * TODO Main Board
  * https://www.we-online.com/components/products/datasheet/744232222.pdf looks like a decent part, and it's got plenty in stock.
  * We're operating at 12MHz, not 480MHz.

* Add LTCC footprint to LPF outline?
  * TODO Metal Case
  * TODO Main Board
  * Added LTCC between LPF and antenna port, with a 1206 0R to bypass it.
  * Added 0603 0R to bypass the discrete filter.
  * Documented which parts to DNP for which design.

* Add 220pF Murata tuned UHF filters.
  * TODO Metal Case
  * TODO Main Board
  * Document to DNP either VHF or UHF, depending on the build.
