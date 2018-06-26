/*
 * BitcoindRpcClient-JSON-RPC-Client License
 * 
 * Copyright (c) 2013, Mikhail Yevchenko.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the 
 * Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject
 * to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
 * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
 * THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
 /*
 * Repackaged with simple additions for easier maven usage by Alessandro Polverini
 */
package wf.bitcoin.javabitcoindrpcclient;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Mikhail Yevchenko m.ṥῥẚɱ.ѓѐḿởύḙ@azazar.com Small modifications by
 * Alessandro Polverini polverini at gmail.com
 */
public interface BitcoindRpcClient {

  public static interface TxInput extends Serializable {

    public String txid();

    public int vout();

    public String scriptPubKey();
  }

  public static class BasicTxInput implements TxInput {

    public String txid;
    public int vout;
    public String scriptPubKey;

    public BasicTxInput(String txid, int vout) {
      this.txid = txid;
      this.vout = vout;
    }

    public BasicTxInput(String txid, int vout, String scriptPubKey) {
      this(txid, vout);
      this.scriptPubKey = scriptPubKey;
    }

    @Override
    public String txid() {
      return txid;
    }

    @Override
    public int vout() {
      return vout;
    }

    @Override
    public String scriptPubKey() {
      return scriptPubKey;
    }

  }

  public static interface TxOutput extends Serializable {

    public String address();

    public double amount();
  }

  public String dumpPrivKey(String address) throws BitcoinRpcException;

  public String getAccount(String address) throws BitcoinRpcException;

  public String getAccountAddress(String address) throws BitcoinRpcException;

  /**
   * @return returns the server's total available balance
   * @throws BitcoinRpcException
   */
  public double getBalance() throws BitcoinRpcException;

  /**
   * @param account
   * @return returns the balance in the account
   * @throws BitcoinRpcException
   */
  public double getBalance(String account) throws BitcoinRpcException;

  /**
   * @param account
   * @param minConf
   * @return returns the balance in the account
   * @throws BitcoinRpcException
   */
  public double getBalance(String account, int minConf) throws BitcoinRpcException;

  public static interface BlockChainInfo extends Serializable {

    public String chain();

    public int blocks();

    public String bestBlockHash();

    public double difficulty();

    public double verificationProgress();

    public String chainWork();
  }

  public static interface DecodedScript extends Serializable {

    public String asm();

    public String hex();

    public String type();

    public int reqSigs();

    public List<String> addresses();

    public String p2sh();
  }

  public static interface Address extends Serializable {

    public String address();

    public String connected();
  }

  public static interface TxOut extends Serializable {
    public String bestBlock();

    public long confirmations();

    public BigDecimal value();

    public ScriptPubKey scriptPubKey();

    public long reqSigs();

    public List<String> addresses();

    public long version();

    public boolean coinBase();
  }

  public static interface Block extends Serializable {

    public String hash();

    public int confirmations();

    public int size();

    public int height();

    public int version();

    public String merkleRoot();

    public List<String> tx();

    public Date time();

    public long nonce();

    public String bits();

    public double difficulty();

    public String previousHash();

    public String nextHash();

    public String chainwork();

    public Block previous() throws BitcoinRpcException;

    public Block next() throws BitcoinRpcException;
  }

  public Block getBlock(int height) throws BitcoinRpcException;

  public Block getBlock(String blockHash) throws BitcoinRpcException;

  public String getBlockHash(int height) throws BitcoinRpcException;

  public BlockChainInfo getBlockChainInfo() throws BitcoinRpcException;

  public int getBlockCount() throws BitcoinRpcException;

  public String getNewAddress() throws BitcoinRpcException;

  public String getNewAddress(String account) throws BitcoinRpcException;

  public String getBestBlockHash() throws BitcoinRpcException;

  public String getRawTransactionHex(String txId) throws BitcoinRpcException;

  public interface ScriptPubKey extends Serializable {

    public String asm();

    public String hex();

    public int reqSigs();

    public String type();

    public List<String> addresses();
  }

  public interface RawTransaction extends Serializable {

    public String hex();

    public String txId();

    public int version();

    public long lockTime();

    public long size();

    public long vsize();

    public String hash();

    /*
     *
     */
    public interface In extends TxInput, Serializable {

      public Map<String, Object> scriptSig();

      public long sequence();

      public RawTransaction getTransaction();

      public Out getTransactionOutput();
    }

    /**
     * This method should be replaced someday
     *
     * @return the list of inputs
     */
    public List<In> vIn(); // TODO : Create special interface instead of this

    public interface Out extends Serializable {

      public double value();

      public int n();

      public ScriptPubKey scriptPubKey();

      public TxInput toInput();

      public RawTransaction transaction();
    }

    /**
     * This method should be replaced someday
     */
    public List<Out> vOut(); // TODO : Create special interface instead of this

    public String blockHash();

    public int confirmations();

    public Date time();

    public Date blocktime();
  }

  public RawTransaction getRawTransaction(String txId) throws BitcoinRpcException;

  public double getReceivedByAddress(String address) throws BitcoinRpcException;

  /**
   * Returns the total amount received by &lt;bitcoinaddress&gt; in transactions
   * with at least [minconf] confirmations. While some might consider this
   * obvious, value reported by this only considers *receiving* transactions. It
   * does not check payments that have been made *from* this address. In other
   * words, this is not "getaddressbalance". Works only for addresses in the
   * local wallet, external addresses will always show 0.
   *
   * @param address
   * @param minConf
   * @return the total amount received by &lt;bitcoinaddress&gt;
   */
  public double getReceivedByAddress(String address, int minConf) throws BitcoinRpcException;

  public void importPrivKey(String bitcoinPrivKey) throws BitcoinRpcException;

  public void importPrivKey(String bitcoinPrivKey, String label) throws BitcoinRpcException;

  public void importPrivKey(String bitcoinPrivKey, String label, boolean rescan) throws BitcoinRpcException;

  /**
   * listaccounts [minconf=1]
   *
   * @return Map that has account names as keys, account balances as values
   * @throws BitcoinRpcException
   */
  public Map<String, Number> listAccounts() throws BitcoinRpcException;

  public Map<String, Number> listAccounts(int minConf) throws BitcoinRpcException;

  public static interface ReceivedAddress extends Serializable {

    public String address();

    public String account();

    public double amount();

    public int confirmations();
  }

  public List<ReceivedAddress> listReceivedByAddress() throws BitcoinRpcException;

  public List<ReceivedAddress> listReceivedByAddress(int minConf) throws BitcoinRpcException;

  public List<ReceivedAddress> listReceivedByAddress(int minConf, boolean includeEmpty) throws BitcoinRpcException;

  /**
   * returned by listsinceblock and listtransactions
   */
  public static interface Transaction extends Serializable {

    public String account();

    public String address();

    public String category();

    public double amount();

    public double fee();

    public int confirmations();

    public String blockHash();

    public int blockIndex();

    public Date blockTime();

    public String txId();

    public Date time();

    public Date timeReceived();

    public String comment();

    public String commentTo();

    public RawTransaction raw();
  }

  public List<Transaction> listTransactions() throws BitcoinRpcException;

  public List<Transaction> listTransactions(String account) throws BitcoinRpcException;

  public List<Transaction> listTransactions(String account, int count) throws BitcoinRpcException;

  public List<Transaction> listTransactions(String account, int count, int from) throws BitcoinRpcException;

  public interface Unspent extends TxInput, TxOutput, Serializable {

    @Override
    public String txid();

    @Override
    public int vout();

    @Override
    public String address();

    public String account();

    public String scriptPubKey();

    @Override
    public double amount();

    public int confirmations();
  }

  public List<Unspent> listUnspent() throws BitcoinRpcException;

  public List<Unspent> listUnspent(int minConf) throws BitcoinRpcException;

  public List<Unspent> listUnspent(int minConf, int maxConf) throws BitcoinRpcException;

  public List<Unspent> listUnspent(int minConf, int maxConf, String... addresses) throws BitcoinRpcException;

  public String move(String fromAccount, String toBitcoinAddress, double amount) throws BitcoinRpcException;

  public String move(String fromAccount, String toBitcoinAddress, double amount, int minConf) throws BitcoinRpcException;

  public String move(String fromAccount, String toBitcoinAddress, double amount, int minConf, String comment) throws BitcoinRpcException;

  public String sendFrom(String fromAccount, String toBitcoinAddress, double amount) throws BitcoinRpcException;

  public String sendFrom(String fromAccount, String toBitcoinAddress, double amount, int minConf) throws BitcoinRpcException;

  public String sendFrom(String fromAccount, String toBitcoinAddress, double amount, int minConf, String comment) throws BitcoinRpcException;

  /**
   * Will send the given amount to the given address, ensuring the account has a
   * valid balance using minConf confirmations.
   *
   * @param fromAccount
   * @param toBitcoinAddress
   * @param amount is a real and is rounded to 8 decimal places
   * @param minConf
   * @param comment
   * @param commentTo
   * @return the transaction ID if successful
   * @throws BitcoinRpcException
   */
  public String sendFrom(String fromAccount, String toBitcoinAddress, double amount, int minConf, String comment, String commentTo) throws BitcoinRpcException;

  public String sendRawTransaction(String hex) throws BitcoinRpcException;

  public String sendToAddress(String toAddress, double amount) throws BitcoinRpcException;

  public String sendToAddress(String toAddress, double amount, String comment) throws BitcoinRpcException;

  /**
   * @param toAddress
   * @param amount is a real and is rounded to 8 decimal places
   * @param comment
   * @param commentTo
   * @return the transaction ID &lt;txid&gt; if successful
   * @throws BitcoinRpcException
   */
  public String sendToAddress(String toAddress, double amount, String comment, String commentTo) throws BitcoinRpcException;

  public String signRawTransaction(String hex) throws BitcoinRpcException;

  public static interface AddressValidationResult extends Serializable {

    public boolean isValid();

    public String address();

    public boolean isMine();

    public boolean isScript();

    public String pubKey();

    public boolean isCompressed();

    public String account();
  }

  public double getEstimateFee(int nBlocks) throws BitcoinRpcException;

  public double getEstimateSmartFee(int nBlocks) throws BitcoinRpcException;

  public double getEstimatePriority(int nBlocks) throws BitcoinRpcException;

  String getRawChangeAddress();

  long getConnectionCount();

  double getUnconfirmedBalance();

  double getDifficulty();

  DecodedScript decodeScript(String hex);

  boolean setTxFee(BigDecimal amount);

  void backupWallet(String destination);

  void dumpWallet(String filename);

  void importWallet(String filename);

  void keyPoolRefill();

  BigDecimal getReceivedByAccount(String account);

  void encryptWallet(String passPhrase);

  void walletPassPhrase(String passPhrase, long timeOut);

  TxOut getTxOut(String txId, long vout);

}
