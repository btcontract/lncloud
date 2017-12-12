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

> use olympus
> db.spentTxs.createIndex( { "hex": 1 }, { unique: true }, { expireAfterSeconds: 3600 * 24 * 365 * 2 } )
> db.spentTxs.createIndex( { "txids": 1 } )

> db.scheduledTxs.createIndex( { "txid": 1 }, { unique: true }, { expireAfterSeconds: 3600 * 24 * 14 } )
> db.scheduledTxs.createIndex( { "cltv": 1 } )

> db.userData.createIndex( { "prefix": 1 }, { unique: true }, { expireAfterSeconds: 3600 * 24 * 365 * 2 } )
> db.userData.createIndex( { "key": 1 } )

> use blindSignatures
> db.blindTokens.createIndex( { "seskey": 1 }, { unique: true }, { expireAfterSeconds: 3600 * 24 * 365 * 1 } )
> "0123456789abcdefABCDEF".split('').forEach(function(v) { db["clearTokens" + v].createIndex( { "token": 1 }, { unique: true } ) })

> use eclair
> db.paymentRequest.createIndex( { "hash": 1 }, { unique: true } )
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
		binding-ip = "127.0.0.1"
		port = 8086
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
java -jar olympus-assembly-1.0.jar production "{\"privKey\":\"00000000000000000000000000000000000000000000000000000000000000000000000000000\",\"price\":{\"amount\":0},\"quantity\":0,\"btcApi\":\"http://foo:bar@127.0.0.1:18333\",\"zmqApi\":\"tcp://127.0.0.1:29003\",\"eclairApi\":\"http://127.0.0.1:8086\",\"eclairSockIp\":\"127.0.0.1\",\"eclairSockPort\":9096,\"eclairNodeId\":\"03dc39d7f43720c2c0f86778dfd2a77049fa4a44b4f0a8afb62f3921567de41375\",\"rewindRange\":1008,\"checkByToken\":true}"
```
