This is an Android VNC server using contemporary Android 5+ APIs. It therefore does not require
root access. In reverence to the venerable [droid-VNC-server](https://github.com/oNaiPs/droidVncServer)
is is called droidVNC-NG.

# Features

* Network export of device frame buffer.
* Injection of remote pointer events.
* Handling of special keys to trigger 'Recent Apps' overview, Home button and Back button.
* Android permission handling.
* Screen rotation handling.
* File transfer via the local network, assuming TightVNC viewer for Windows version 1.3.x is used.
* Password protection for secure-in-terms-of-VNC connection.
* Ability to specify the port used.
* Start of background service on device boot.
* Reverse VNC.

# Notes

Requires at least Android 7.

[Since Android 10](https://developer.android.com/about/versions/10/privacy/changes#screen-contents),
the permission to access the screen contents has to be given on each start and is not saved.
