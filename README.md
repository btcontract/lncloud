# olympus
Maintenance server for Lightning Wallet

### Installation manual for Ubuntu 16.04

1. Install Java by following steps described at https://www.digitalocean.com/community/tutorials/how-to-install-java-with-apt-get-on-ubuntu-16-04

2. Install Bitcoin Core:
```
sudo apt-get update  
sudo apt-get install bitcoind
```

3. Bitcoin config file should contain the following lines: 
```
daemon=1
server=1

rpcuser=foo # set your own
rpcpassword=bar # set your own

rpcport=18332
port=8333

txindex=1 # recommended but not necessary
# prune=100000 not recommended but won't break anything if turned on, useful if you have less than 200Gb of disk space

zmqpubrawtx=tcp://127.0.0.1:29000
zmqpubhashblock=tcp://127.0.0.1:29000
zmqpubrawblock=tcp://127.0.0.1:29000
```

4. Install and run a MongoDB by following steps described at https://docs.mongodb.com/manual/tutorial/install-mongodb-on-ubuntu/

5. Open MongoDB console and issue the following commands:
```
$ mongo

> use btc-olympus
> db.spentTxs.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 90 } )
> db.spentTxs.createIndex( { "prefix": 1 }, { unique: true } )
> db.spentTxs.createIndex( { "txids": 1 } )

> db.scheduledTxs.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 30 } )
> db.scheduledTxs.createIndex( { "cltv": 1 } )

> db.userData.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 365 * 4 } )
> db.userData.createIndex( { "key": 1 } )

> db.chanInfo.createIndex( { "shortChanId": 1 } )
> db.chanInfo.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 365 } )

> use btc-blindSignatures
> db.blindTokens.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 365 } )
> db.blindTokens.createIndex( { "seskey": 1 }, { unique: true } )

> "0123456789".split('').forEach(function(v) { db["clearTokens" + v].createIndex( { "token": 1 }, { unique: true } ) })

> use btc-watchedTxs
> db.watchedTxs.createIndex( { "createdAt": 1 }, { expireAfterSeconds: 3600 * 24 * 360 * 2 } )
> db.watchedTxs.createIndex( { "halfTxId": 1 } )
```

6. Get Eclair fat JAR file, either by downloading it directly from a repository or by compiling from source:  
```
git clone https://github.com/ACINQ/eclair.git  
cd eclair  
mvn package  
```

7. Create an `eclairdata` directory and put an `eclair.conf` file there with the following lines:
```
eclair {
	chain = "test"
	spv = false

	server {
		public-ips = ["127.0.0.1"]
		binding-ip = "0.0.0.0"
		port = 9096
	}

	api {
		enabled = true
		binding-ip = "127.0.0.1"
		port = 8086
		password = "pass"
	}

	bitcoind {
		host = "localhost"
		rpcport = 18332
		rpcuser = "foo"
		rpcpassword = "bar"
		zmq = "tcp://127.0.0.1:29000"
	}
}

```

8. Run Ecliar instance by issuing `java -Declair.datadir=eclairdata/ -jar eclair-node.jar`

9. Get Olympus fat JAR file, either by downloading it directly from a repository or by compiling from source: 
```
git clone https://github.com/btcontract/olympus.git  
cd olympus  
sbt  
assembly  
```

10. Run Olympus instance by issuing:
```
$ java -jar olympus-assembly-1.0.jar production "{
\"zmqApi\":\"tcp://127.0.0.1:19000\", // Bitcoin ZeroMQ endpoint
\"ip\":\"192.3.114.77\", // Olympus API will be accessible at this address...
\"port\":9203, // ...and this port
\"privKey\":\"17237641984433455757821928886025053286790003625266087739786982589470995742521\", // To blind-sign storage tokens
\"btcApi\":\"http://foo:bar@127.0.0.1:19332\", // Bitcoin Json-RPC endpoint
\"eclairSockPort\":9935, // Eclair port
\"rewindRange\":14, // How many blocks to inspect on restart if Olympus was offline for some time, important for watchtower
\"eclairSockIp\":\"192.3.114.77\", // Eclair address
\"eclairNodeId\":\"02330d13587b67a85c0a36ea001c4dba14bcd48dda8988f7303275b040bffb6abd\",
\"paymentProvider\":{\"quantity\":50,\"priceMsat\":5000000,\"url\":\"http://192.3.114.77:8089\",\"description\":\"50 storage tokens for backup Olympus server at a.lightning-wallet.com\",\"tag\":\"EclairProvider\",\"pass\":\"password\"},
\"sslFile\":\"keystore.jks\",
\"sslPass\":\"pass123\",
\"minCapacity\":10000 // Channels below this value will be excluded from graph due to low chance of routing success
}"
```

Note: Olympus config is provided as a command line argument instead of a file because it contains private keys (the one for storage tokens and for Strike). Don't forget to use space before issuing a command (i.e. `$ java -jar ...`, NOT `$java - jar ...`) so it does not get recorded in history.
