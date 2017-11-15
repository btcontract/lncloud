# olympus
Maintenance server for Lightning Wallet

### Installation manual for Ubuntu 16.04

1. Install Java by following steps described at https://www.digitalocean.com/community/tutorials/how-to-install-java-with-apt-get-on-ubuntu-16-04

2. Install Bitcoin Core e.g.  
`sudo apt-get update`  
`sudo apt-get install bitcoind`

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
> db.spentTxs.createIndex( { "hex": 1 }, { unique: true } )
> db.spentTxs.createIndex( { "txids": 1 }, { expireAfterSeconds: 3600 * 24 * 365 * 2 } )
> db.userData.createIndex( { "key": 1 }, { expireAfterSeconds: 3600 * 24 * 365 * 2 } )
> db.userData.createIndex( { "prefix": 1 }, { unique: true } )
> db.scheduledTxs.createIndex( { "txid": 1 }, { expireAfterSeconds: 3600 * 24 * 14 } )
```

6. Get Eclair fat JAR file, either by downloading it directly from a repository or by compiling from source:  
`git clone https://github.com/btcontract/olympus.git`  
`cd eclair`  
`mvn package`  

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
`git clone https://github.com/ACINQ/eclair.git`  
`cd olympus`  
`sbt`  
`assembly`  

10. Run Olympus instance by issuing:
```
java -jar olympus-assembly-1.0.jar production {
	"privKey":"33337641954423495759821968886025053266790003625264088739786982511471995762588", // not important for private Olympus instance
	"price":{"amount":2000000}, // not important for private Olympus instance
	"quantity":50, // not important for private Olympus instance
	"btcApi":"http://foo:bar@127.0.0.1:18332",
	"zmqApi":"tcp://127.0.0.1:29000",
	"eclairApi":"http://127.0.0.1:8086",
	"eclairSockIp":"127.0.0.1",
	"eclairSockPort":9096,
	"eclairNodeId":"0299439d988cbf31388d59e3d6f9e184e7a0739b8b8fcdc298957216833935f9d3",
	"rewindRange":1008,
	"checkByToken":false
}
```
