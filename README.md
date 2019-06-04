# Account
## message Account
	message Account {
	    bytes address = 1;      	// account address
	    AccountValue value = 2; 	// account value
	}

## message AccountValue
	message AccountValue {
	    int32 nonce = 1;						// transaction index
	    bytes balance = 2;						// cwb balance
	    bytes max = 3;							// max transfer per day, used in union-account
	    bytes acceptMax = 4;					// max transfer per request, used in union-account
	    int32 acceptLimit = 5;					// signature count with senders, used in union-account
	    repeated bytes address = 6;				// child accounts contains in main account, used in union-account
	    repeated AccountTokenValue tokens = 7;	// token balance, see [AccountTokenValue]
	    repeated AccountCryptoValue cryptos = 8;// crypto balance, see [AccountCryptoValue]
	    bytes storage = 9;						// contract storage, used in contract
	    bytes codeHash = 10;					// contract code hash, used in contract
	    bytes code = 11;						// contract code, used in contract
	    bytes data = 12;						// contract data, used in contract
	}

## message AccountTokenValue
	message AccountTokenValue {
	    string token = 1;		// token name
	    bytes balance = 2;		// token available balance
	    bytes locked = 3;		// token locked balance
	}
	
	message AccountCryptoValue {
	    string symbol = 1; 						// crypto-token name
	    repeated AccountCryptoToken tokens = 2;	// crypto-token balance, see [AccountCryptoToken]
	}
	
	message AccountCryptoToken {
	    bytes hash = 1; 		// crypto-token hash
	    int64 timestamp = 2; 	// crypto-token created time
	    int32 index = 3; 		// crypto-token index
	    int32 total = 4; 		// total number of crypto-token issued
	    bytes code = 5; 		// crypto-token code
	    bytes name = 6; 		// crypto-token name
	    bytes owner = 7; 		// owner address
	    int32 nonce = 8; 		// total transfer count
	    int64 ownertime = 9; 	// own time
	}

# Transaction
## message MultiTransaction
	message MultiTransaction {
		string txHash = 1;					// transaction hash
		MultiTransactionBody txBody = 2;	// transaction content, see [MultiTransactionBody]
		string status = 3;					// execute status
		MultiTransactionNode txNode = 4;    // creator node info, see [MultiTransactionNode]
		bytes result = 5;					// execute result
	} 

## message MultiTransactionBody
	message MultiTransactionBody {
		repeated MultiTransactionInput inputs = 1;			// transaction inputs, see [MultiTransactionInput]
		repeated MultiTransactionOutput outputs = 2;		// transaction outputs, see [MultiTransactionOutput]
		bytes exdata = 3;									// store some extended data, used in contract or something else
		repeated MultiTransactionSignature signatures = 4;	// transaction signatures, see [MultiTransactionSignature]
		repeated bytes delegate = 5;						// (unavailable)
		bytes data = 6;										// data, used in contract
		int64 timestamp = 7;								// created time
		int32 type = 8;										// transaction type, for now it has [transfer CWB, create token, transfer token, lock token, create contract, call contract, transfer crypto-token ]
	}

## message MultiTransactionInput
	message MultiTransactionInput {
		int32 nonce = 1;		// sender transaction index
		bytes address = 4;		// sender address
		bytes amount= 5;		// input amount, it can be CWB or token amount depend on different transaction type
		string token = 7;		// token name, available in transaction with type create or transfer token
		string symbol = 8;		// crypto-token name, available in transfer crypto-token transaction
		bytes cryptoToken = 9;	// crypto-token hash, available in transfer crypto-token transaction
	}
	
## message MultiTransactionOutput
	message MultiTransactionOutput {
		bytes address= 1;		// receiver address
		bytes amount= 2;		// receive amount, it can be CWB or token amount depend on different transaction type
		string symbol = 3;		// crypto-token name, available in transfer crypto-token transaction
		bytes cryptoToken = 4;	// crypto-token hash, available in transfer crypto-token transaction
	}
	
## message MultiTransactionSignature
	message MultiTransactionSignature {
		bytes signature = 1;	// signature result
	}
	
## message MultiTransactionNode
	message MultiTransactionNode {
		string node = 1;		// created node name
		bytes address = 2;		// created node account address
		string bcuid = 3;		// other node information
	}

# Block
## message BlockEntity
	message BlockEntity {
		BlockHeader header = 1;	// block header, see [BlockHeader]
		BlockBody body = 2;		// block body, see [BlockBody]
		BlockMiner miner = 3;	// block miner, see [BlockMiner]
		int32 version = 50;		// for now the version is 1
	}
	
## message BlockHeader
	message BlockHeader {
		string parentHash = 1;			// parent block hash
		string stateRoot = 2;			// account state mpt-trie root
		string txTrieRoot = 3;			// transaction mpt-trie root
		string receiptTrieRoot = 4;		// transaction receipt mpt-trie root
		int64 timestamp = 5;			// block created time
		int64 number = 6;				// block height
		string extraData = 8;			// extend data
		string blockHash = 9;			// current block hash
		repeated string txHashs = 10;	// transaction hash list
		int64 sliceId = 11;				// (unavailable)
	}
	
## message BlockBody
	message BlockBody {
		repeated MultiTransaction txs = 1; // transaction full content, see [MultiTransaction]
	}
	
## message BlockMiner
	message BlockMiner {
		string node = 1;	// the node name of the block miner 
		string reward = 3;	// block miner reward
		string address = 4;	// the node account address of the block miner
		string bcuid = 5;	// the other information of the block miner
	}

# ACCOUNT API
>all data is for sample only!

## POST act/pbgac.do
	return account value by account address. same with the contract. 
### request
	{
		"address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa"
	}
### response
	{
	    "retCode": 1,
	    "address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa",
	    "account": {
	        "nonce": 1,
	        "balance": "1998766000000000000000000",
	        "tokens": [],
	        "cryptos": [],
	        "max": "0",
	        "acceptMax": "0",
	        "acceptLimit": 0,
	        "storage": "ec9520c34b0a15a01bb4f583e52393f75de9c28ed757fa950801120b01a741526cebde61f800001a0100220100",
	        "accountAddress": "c34b0a15a01bb4f583e52393f75de9c28ed757fa",
	        "code": "",
	        "codeHash": "",
	        "data": ""
	    }
	}

# TRANSACTION API
>all data is for sample only!

## POST txt/pbmtx.do
	create new transaction on node. you can do a lot of transaction with this api. different type of transaction has the different request, see below:
### CWB transfer
	{ 
		"txBody": {
			"inputs": [{
				"nonce": "0",
				"address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa",
				"amount": "10000000000000000000"
			}],
			"outputs": [{
				"address": "52d06fd0ff2d7c0ea5a74484094c7fab8ec81c43",
				"amount": "10000000000000000000"
			}],
			"exdata": "",
			"signatures": [{
				"signature": "4a85b6ad5b1385470a152b694e0c2a38666d1f633001f5a565f1887709fe7ab8101515bcc8a49e88e89c98fd91060a56a44fd4556dad36b8f9f6e1dfd663c945c34b0a15a01bb4f583e52393f75de9c28ed757fa96605f60dc8874d4228e906a60b184e04de4099828beedd3ca47e76117288362bf37a6f5fb05727e0234db665cff17ea9edaaf2b8b4c4ececdfa069317183d68"
			}],
			"type": "0"
		}
	}
### create token
	{ 
		"txBody": {
			"inputs": [{
				"nonce": "0",
				"address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa",
				"amount": "10000000000000000000000000000",
				"token": "ABC"
			}],
			"exdata": "",
			"signatures": [{
				"signature": "4a85b6ad5b1385470a152b694e0c2a38666d1f633001f5a565f1887709fe7ab8101515bcc8a49e88e89c98fd91060a56a44fd4556dad36b8f9f6e1dfd663c945c34b0a15a01bb4f583e52393f75de9c28ed757fa96605f60dc8874d4228e906a60b184e04de4099828beedd3ca47e76117288362bf37a6f5fb05727e0234db665cff17ea9edaaf2b8b4c4ececdfa069317183d68"
			}],
			"type": "9"
		}
	}	
### transfer token
	{ 
		"txBody": {
			"inputs": [{
				"nonce": "0",
				"address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa",
				"amount": "10000000000000000000",
				"token": "ABC"
			}],
			"outputs": [{
				"address": "52d06fd0ff2d7c0ea5a74484094c7fab8ec81c43",
				"amount": "10000000000000000000"
			}],
			"exdata": "",
			"signatures": [{
				"signature": "4a85b6ad5b1385470a152b694e0c2a38666d1f633001f5a565f1887709fe7ab8101515bcc8a49e88e89c98fd91060a56a44fd4556dad36b8f9f6e1dfd663c945c34b0a15a01bb4f583e52393f75de9c28ed757fa96605f60dc8874d4228e906a60b184e04de4099828beedd3ca47e76117288362bf37a6f5fb05727e0234db665cff17ea9edaaf2b8b4c4ececdfa069317183d68"
			}],
			"type": "2"
		}
	}
### lock token
	{ 
		"txBody": {
			"inputs": [{
				"nonce": "0",
				"address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa",
				"amount": "10000000000000000000",
				"token": "ABC"
			}],
			"outputs": [{
				"address": "52d06fd0ff2d7c0ea5a74484094c7fab8ec81c43",
				"amount": "10000000000000000000"
			}],
			"exdata": "",
			"signatures": [{
				"signature": "4a85b6ad5b1385470a152b694e0c2a38666d1f633001f5a565f1887709fe7ab8101515bcc8a49e88e89c98fd91060a56a44fd4556dad36b8f9f6e1dfd663c945c34b0a15a01bb4f583e52393f75de9c28ed757fa96605f60dc8874d4228e906a60b184e04de4099828beedd3ca47e76117288362bf37a6f5fb05727e0234db665cff17ea9edaaf2b8b4c4ececdfa069317183d68"
			}],
			"type": "6"
		}
	}
### transfer crypto-token 
	{ 
		"txBody": {
			"inputs": [{
				"nonce": "0",
				"address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa",
				"symbol": "HUS",
				"cryptoToken": "crypto-token"
			}],
			"outputs": [{
				"address": "52d06fd0ff2d7c0ea5a74484094c7fab8ec81c43",
				"symbol": "HUS",
				"cryptoToken": "crypto-token",
			}],
			"exdata": "",
			"signatures": [{
				"signature": "4a85b6ad5b1385470a152b694e0c2a38666d1f633001f5a565f1887709fe7ab8101515bcc8a49e88e89c98fd91060a56a44fd4556dad36b8f9f6e1dfd663c945c34b0a15a01bb4f583e52393f75de9c28ed757fa96605f60dc8874d4228e906a60b184e04de4099828beedd3ca47e76117288362bf37a6f5fb05727e0234db665cff17ea9edaaf2b8b4c4ececdfa069317183d68"
			}],
			"type": "5"
		}
	}
### create contract
	{ 
		"txBody": {
			"inputs": [{
				"nonce": "0",
				"address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa"
			}],
			"exdata": "",
			"signatures": [{
				"signature": "4a85b6ad5b1385470a152b694e0c2a38666d1f633001f5a565f1887709fe7ab8101515bcc8a49e88e89c98fd91060a56a44fd4556dad36b8f9f6e1dfd663c945c34b0a15a01bb4f583e52393f75de9c28ed757fa96605f60dc8874d4228e906a60b184e04de4099828beedd3ca47e76117288362bf37a6f5fb05727e0234db665cff17ea9edaaf2b8b4c4ececdfa069317183d68"
			}],
			"data": "608060405260008055348015601357600080fd5b5060a2806100226000396000f300608060405260043610603f576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff16806367e0badb146044575b600080fd5b604a6060565b6040518082815260200191505060405180910390f35b60006001600054016000819055506000549050905600a165627a7a7230582062fd3e962a4692878af614957db732bc3517dcb6324935eeb68878c0f30cb84b0029",
			"type": "7"
		}
	}
### call contract	
	{ 
		"txBody": {
			"inputs": [{
				"nonce": "0",
				"address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa"
			}],
			"outputs": [{
				"address": "4508684405249762081ae8fdadc963d01de8904d08cbb85adc4216c6049aedba"
			}],
			"exdata": "",
			"signatures": [{
				"signature": "4a85b6ad5b1385470a152b694e0c2a38666d1f633001f5a565f1887709fe7ab8101515bcc8a49e88e89c98fd91060a56a44fd4556dad36b8f9f6e1dfd663c945c34b0a15a01bb4f583e52393f75de9c28ed757fa96605f60dc8874d4228e906a60b184e04de4099828beedd3ca47e76117288362bf37a6f5fb05727e0234db665cff17ea9edaaf2b8b4c4ececdfa069317183d68"
			}],
			"data": "67e0badb",
			"type": "8"
		}
	}
### response
	when request create contract transaction, it will response transaction hash and contract address. the other request only response transaction hash.
	
	{
		"txHash": "",
		"contractHash": "",
		"retCode": 1,
		"retMsg": ""
	}

## GET txt/pbgtx.do?hexTxHash=
### response
	{
	    "transaction": {
	        "txHash": "e0a221bd175f1628421697421bc8c8f199674280dfacaf5c77b7125e6c0efce7",
	        "txBody": {
	            "inputs": [
	                {
	                    "nonce": 0,
	                    "address": "c34b0a15a01bb4f583e52393f75de9c28ed757fa",
	                    "amount": "1234000000000000000000",
	                    "token": "",
	                    "symbol": "",
	                    "cryptoToken": ""
	                }
	            ],
	            "outputs": [
	                {
	                    "address": "52d06fd0ff2d7c0ea5a74484094c7fab8ec81c43",
	                    "amount": "1234000000000000000000",
	                    "symbol": "",
	                    "cryptoToken": ""
	                }
	            ],
	            "signatures": [
	                {
	                    "signature": "4a85b6ad5b1385470a152b694e0c2a38666d1f633001f5a565f1887709fe7ab8101515bcc8a49e88e89c98fd91060a56a44fd4556dad36b8f9f6e1dfd663c945c34b0a15a01bb4f583e52393f75de9c28ed757fa96605f60dc8874d4228e906a60b184e04de4099828beedd3ca47e76117288362bf37a6f5fb05727e0234db665cff17ea9edaaf2b8b4c4ececdfa069317183d68"
	                }
	            ],
	            "type": 0,
	            "data": "",
	            "exdata": "",
	            "timestamp": 1531123143553
	        },
	        "txNode": {
	        	"node": "",
	        	"address": "",
	        	"bcuid": ""
	        },
	        "status": "done"
	        "result": ""
	    },
	    "retCode": 1
	    "retMsg": ""
	}

# BLOCK API
>all data is for sample only!

## GET/POST bct/pbgbh.do?hash=
### response
	{
		"header": {
			"parentHash": "",
			"txTrieRoot": "",
			"timestamp": "",
			"number": "",
			"extraData": "",
			"blockHash": "",
			"txHashs": []
			"sliceId": "",
			"state": "",
			"receipt": ""
		},
		"miner": {},
		"version": "",
		"retCode": "",
		"retMsg": ""
	}

## GET/POST bct/pbgbn.do?number=
### response