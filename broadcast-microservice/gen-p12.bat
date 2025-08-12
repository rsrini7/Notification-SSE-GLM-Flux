del keystore.p12
keytool -genkeypair -alias netty -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore keystore.p12 -validity 3650 -storepass password -keypass password -dname "CN=localhost, OU=IT, O=MyCompany, L=Bangalore, ST=Karnataka, C=IN"
copy keystore.p12 broadcast-admin-service\src\main\resources\
move keystore.p12 broadcast-user-service\src\main\resources\