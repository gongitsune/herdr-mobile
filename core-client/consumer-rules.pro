# JSch resolves algorithms, authenticators and crypto implementations by class name.
-keep class com.jcraft.jsch.** { *; }
# BC is used by JSch's reflective adapters and our Ed25519 key generation.
# Start conservatively; shrink these rules only with a minified API-26 device test.
-keep class org.bouncycastle.** { *; }

# Optional JSch integrations not shipped/selected by this client.
-dontwarn com.jcraft.jzlib.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
# Optional BC JNDI certificate-store helpers are not part of the SSH path.
-dontwarn javax.naming.**
