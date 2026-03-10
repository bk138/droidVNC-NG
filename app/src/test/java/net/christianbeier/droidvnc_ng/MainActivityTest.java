package net.christianbeier.droidvnc_ng;

import org.junit.Test;

import static org.junit.Assert.*;

public class MainActivityTest {

    @Test
    public void isNonLocalHost_privateRfc1918_returnsFalse() {
        assertFalse(MainActivity.isNonLocalHost("10.0.0.1"));
        assertFalse(MainActivity.isNonLocalHost("10.255.255.255"));
        assertFalse(MainActivity.isNonLocalHost("172.16.0.1"));
        assertFalse(MainActivity.isNonLocalHost("172.31.255.255"));
        assertFalse(MainActivity.isNonLocalHost("192.168.0.1"));
        assertFalse(MainActivity.isNonLocalHost("192.168.1.100"));
    }

    @Test
    public void isNonLocalHost_loopback_returnsFalse() {
        assertFalse(MainActivity.isNonLocalHost("127.0.0.1"));
        assertFalse(MainActivity.isNonLocalHost("127.1.2.3"));
        assertFalse(MainActivity.isNonLocalHost("localhost"));
        assertFalse(MainActivity.isNonLocalHost("LOCALHOST"));
    }

    @Test
    public void isNonLocalHost_publicIpOrDomain_returnsTrue() {
        assertTrue(MainActivity.isNonLocalHost("8.8.8.8"));
        assertTrue(MainActivity.isNonLocalHost("1.1.1.1"));
        assertTrue(MainActivity.isNonLocalHost("203.0.113.1"));
        assertTrue(MainActivity.isNonLocalHost("example.com"));
        assertTrue(MainActivity.isNonLocalHost("my.viewer.net"));
    }
}
