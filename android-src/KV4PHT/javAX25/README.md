# javAX25
## AX.25 Modem in Java, for AFSK and APRS usage with Hamradio and soundcards.

- First of all, this repository is a fork from [sivantoledo/javAX25](https://github.com/sivantoledo/javAX25), based in the great job of [Sivan Toledo](https://github.com/sivantoledo), so all credits goes to him. Sivan also wrote a documentation that describes how the original source-code was done. I've added this doc in [the repository wiki](https://github.com/damico/javAX25/wiki/Manual:-AX25-Java-Soundcard-Modem).
- This project also tries to solve this question: https://www.reddit.com/r/amateurradio/comments/3d89f1/headless_aprs_for_linux/
- The APRS message packet definition can be found here http://www.aprs.org/doc/APRS101.PDF

## Compiling

- Just clone this repository and run the following maven command `mvn clean install -DskipTests`. I recommend **-DskipTests** argument because some application maybe is already using your soundcard, then the maven process will break.

## Audio Example:


https://user-images.githubusercontent.com/692043/137969936-fce46995-3776-4591-bc1e-df8f9f00299d.mp4



### Python alternative
- For Python users and programmers there is a similar project for encode AFSK over AX.25 protocol: https://pypi.org/project/afsk/ and https://github.com/casebeer/afsk
