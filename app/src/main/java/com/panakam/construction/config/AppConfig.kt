package com.panakam.construction.config

/**
 * Single source of truth for the backend URL.
 *
 * - Local dev (emulator on same Mac):        http://10.0.2.2:8080
 * - Local dev (physical device, same WiFi):  http://<Mac LAN IP>:8080
 * - Production (AWS EC2, HTTPS via Nginx +
 *   Let's Encrypt, free nip.io domain):      https://13.206.219.67.nip.io
 *   (nip.io maps <ip>.nip.io -> that IP automatically, no DNS signup needed.
 *   If you later buy a real domain, just point an A record at the Elastic IP
 *   and re-run: sudo certbot --nginx -d yourdomain.com)
 *
 * Change ONLY this constant — DatabaseManager and AuthManager both read it from here.
 */
object AppConfig {
    const val BASE_URL = "https://13.206.219.67.nip.io"
}

