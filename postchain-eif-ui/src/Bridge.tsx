import { formatUnits } from "@ethersproject/units";
import { useWeb3React } from "@web3-react/core";
import { BigNumber, ethers } from "ethers";
import { toast } from "react-hot-toast";
import React, { useEffect, useState } from "react";
import { useQuery } from "react-query";

import ERC20TokenArtifacts from "./postchain-eif-contracts/artifacts/@openzeppelin/contracts/token/ERC20/ERC20.sol/ERC20.json";
import ERC721TokenArtifacts from "./postchain-eif-contracts/artifacts/@openzeppelin/contracts/token/ERC721/ERC721.sol/ERC721.json";
import BridgeArtifacts from "./postchain-eif-contracts/artifacts/contracts/TokenBridge.sol/TokenBridge.json";

import { restClient, gtxClient, util } from "postchain-client"
import { hexZeroPad, keccak256 } from "ethers/lib/utils";
import { intToHex } from "ethjs-util";
import { Signature } from "./types";
import { createAuthDesc } from "./util";

const postchainURL = process.env.REACT_APP_POSTCHAIN_URL || ""
const blockchainRID = process.env.REACT_APP_POSTCHAIN_BRID || ""
const rest = restClient.createRestClient(postchainURL, blockchainRID, 5);
const client = gtxClient.createClient(
  rest,
  Buffer.from(blockchainRID, 'hex'),
  []
)

interface Props {
  bridgeAddress: string;
  tokenAddress: string;
}

const sendTnx = async (signer, to, calldata) => {
  const txPrams = {
    to: to,
    value: '0x0',
    data: calldata
  };
  const transaction = await signer.sendTransaction(txPrams);
  toast.promise(transaction.wait(), {
    loading: `Transaction submitted. Wait for confirmation...`,
    success: <b>Transaction confirmed!</b>,
    error: <b>Transaction failed!.</b>,
  })
}

const TokenInfo = ({ tokenAddress, bridgeAddress, tokenType, tokenId }: { tokenAddress: string, bridgeAddress: string, tokenType: string, tokenId: number }) => {
  const { library, chainId, account } = useWeb3React();
  const [accountId, setAccountId] = useState("")
  const [accountNUmber, setAccountNumber] = useState("")
  const [blockHeight, setBlockHeight] = useState("")
  const fetchTokenInfo = async () => {
    var tokenContract;
    let balance;
    let withdraws;
    if (tokenType === "ERC721") {
      tokenContract = new ethers.Contract(tokenAddress, ERC721TokenArtifacts.abi, library);
      const hasToken = await client.query('evm_has_erc721', { "network_id": chainId, "token_address": tokenAddress.toLowerCase(), "beneficiary": account.toLowerCase(), "token_id": tokenId })
      balance = hasToken ? 1 : 0;
      withdraws = await client.query('get_erc721_withdrawal', {
        'network_id': chainId,
        'token_address': tokenAddress.toLowerCase(),
        'token_id': tokenId,
        'beneficiary': account.toLowerCase()
      });
    } else {
      tokenContract = new ethers.Contract(tokenAddress, ERC20TokenArtifacts.abi, library);
      balance = await client.query('evm_balance_of_erc20', { "network_id": chainId, "token_address": tokenAddress.toLowerCase(), "beneficiary": account.toLowerCase() })
      withdraws = await client.query('get_erc20_withdrawal', {
        'network_id': chainId,
        'token_address': tokenAddress.toLowerCase(),
        'beneficiary': account.toLowerCase()
      });
    }
    const name = await tokenContract.name();
    const symbol = await tokenContract.symbol();
    var decimals = 0;
    if (tokenType === "ERC20") {
      decimals = await tokenContract.decimals();
    }
    balance = balance.toString()
    return {
      name,
      symbol,
      decimals,
      balance,
      withdraws
    }
  }

  const { error, isLoading, data } = useQuery(["token-info", tokenAddress], fetchTokenInfo, {
    enabled: tokenAddress !== "",
  });

  if (error) return <div>failed to load</div>
  if (isLoading) return <div>loading...</div>

  var DecodeHexStringToByteArray = function (hexString: string) {
    var result = [];
    while (hexString.length >= 2) {
      result.push(parseInt(hexString.substring(0, 2), 16))
      hexString = hexString.substring(2, hexString.length)
    }
    return result;
  }

  const calculateEventLeafHash = (...args: any) => {
    let event: string = ''
    args.forEach(arg => {
      if (typeof arg === 'number') {
        event += hexZeroPad(intToHex(arg), 32).substring(2)
      } else if (typeof arg === 'string') {
        event += hexZeroPad(arg, 32).substring(2)
      }
    })
    let eventHash = keccak256(DecodeHexStringToByteArray(event))
    return eventHash.substring(2, eventHash.length)
  }

  const withdrawRequest = async (eventHash: string) => {
    const signer = library.getSigner()
    try {
      let data = await client.query('get_event_merkle_proof', { "eventHash": eventHash })
      let event = JSON.parse(JSON.stringify(data))
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )

      const blockHeader = "0x" + event.blockHeader
      const blockWitness = event.blockWitness
      blockWitness.sort((a, b) => (a.pubkey > b.pubkey) ? 1 : ((a.pubkey < b.pubkey) ? -1 : 0))
      let sigs = new Array<string>(blockWitness.length)
      let signers = new Array<string>(blockWitness.length)
      for (let i = 0; i < blockWitness.length; i++) {
        sigs[i] = "0x" + blockWitness[i].sig
        signers[i] = "0x" + blockWitness[i].pubkey
      }

      const eventData = "0x" + event.eventData
      const eventProof = event.eventProof
      let merkleProofs = new Array<String>(eventProof.merkleProofs.length)
      for (let i = 0; i < eventProof.merkleProofs.length; i++) {
        merkleProofs[i] = "0x" + eventProof.merkleProofs[i]
      }
      const evtProof = {
        leaf: "0x" + eventProof.leaf,
        position: eventProof.position,
        merkleProofs: merkleProofs,
      }
      const extraMerkleProof = event.extraMerkleProof
      let extraMerkleProofs = new Array<String>(extraMerkleProof.extraMerkleProofs.length)
      for (let i = 0; i < extraMerkleProof.extraMerkleProofs.length; i++) {
        extraMerkleProofs[i] = "0x" + extraMerkleProof.extraMerkleProofs[i]
      }
      const extraProof = {
        leaf: "0x" + extraMerkleProof.leaf,
        hashedLeaf: "0x" + extraMerkleProof.hashedLeaf,
        position: extraMerkleProof.position,
        extraRoot: "0x" + extraMerkleProof.extraRoot,
        extraMerkleProofs: extraMerkleProofs,
      }
      var calldata
      if (tokenType === "ERC20") {
        calldata = bridge.interface.encodeFunctionData("withdrawRequest", [eventData, evtProof, blockHeader, sigs, signers, extraProof])
      } else {
        calldata = bridge.interface.encodeFunctionData("withdrawRequestNFT", [eventData, evtProof, blockHeader, sigs, signers, extraProof])
      }
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
      console.log(error)
    }
  }

  const withdrawBySnapshot = async () => {
    const signer = library.getSigner()
    try {
      let data = await client.query('get_account_state_merkle_proof', { "blockHeight": BigNumber.from(blockHeight), "accountNumber": BigNumber.from(accountNUmber)})
      let state = JSON.parse(JSON.stringify(data))
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )

      const blockHeader = "0x" + state.blockHeader
      const blockWitness = state.blockWitness
      blockWitness.sort((a, b) => (a.pubkey > b.pubkey) ? 1 : ((a.pubkey < b.pubkey) ? -1 : 0))
      let sigs = new Array<string>(blockWitness.length)
      let signers = new Array<string>(blockWitness.length)
      for (let i = 0; i < blockWitness.length; i++) {
        sigs[i] = "0x" + blockWitness[i].sig
        signers[i] = "0x" + blockWitness[i].pubkey
      }

      const accountState = state.accountState
      const account = {
        blockHeight: accountState.blockHeight,
        accountNumber: accountState.accountNumber,
      }
      const snapshot = "0x" + accountState.snaphot

      const stateProofs = state.stateProofs
      let merkleProofs = new Array<String>(stateProofs.length)
      for (let i = 0; i < stateProofs.length; i++) {
        merkleProofs[i] = "0x" + stateProofs[i]
      }

      const extraMerkleProof = state.extraMerkleProof
      let extraMerkleProofs = new Array<String>(extraMerkleProof.extraMerkleProofs.length)
      for (let i = 0; i < extraMerkleProof.extraMerkleProofs.length; i++) {
        extraMerkleProofs[i] = "0x" + extraMerkleProof.extraMerkleProofs[i]
      }
      const extraProof = {
        leaf: "0x" + extraMerkleProof.leaf,
        hashedLeaf: "0x" + extraMerkleProof.hashedLeaf,
        position: extraMerkleProof.position,
        extraRoot: "0x" + extraMerkleProof.extraRoot,
        extraMerkleProofs: extraMerkleProofs,
      }
      let calldata = bridge.interface.encodeFunctionData("withdrawBySnapshot", [account, snapshot, stateProofs, blockHeader, sigs, signers, extraProof])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
      console.log(error)
    }
  }

  const withdraw = async (eventHash: string) => {
    const signer = library.getSigner();
    const zeroPaddedEventHash = "0x" + eventHash
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      var calldata
      if (tokenType === "ERC20") {
        calldata = bridge.interface.encodeFunctionData("withdraw", [zeroPaddedEventHash, account])
      } else {
        calldata = bridge.interface.encodeFunctionData("withdrawNFT", [zeroPaddedEventHash, account])
      }
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
      console.log(error)
    }
  }

  const withdrawToPostchain = async (eventHash: string) => {
    const signer = library.getSigner();
    const zeroPaddedEventHash = "0x" + eventHash
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      var calldata
      if (tokenType === "ERC20") {
        calldata = bridge.interface.encodeFunctionData("withdrawToPostchain", [zeroPaddedEventHash, Buffer.from(accountId, 'hex')])
      } else {
        calldata = bridge.interface.encodeFunctionData("withdrawNFT2Postchain", [zeroPaddedEventHash, Buffer.from(accountId, 'hex')])
      }
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
      console.log(error)
    }
  }  

  const pending = async (eventHash: string) => {
    const signer = library.getSigner();
    const zeroPaddedEventHash = "0x" + eventHash
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      let calldata = bridge.interface.encodeFunctionData("pendingWithdraw", [zeroPaddedEventHash])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (e) {
      console.log(e.Message)
    }
  }

  const unpending = async (eventHash: string) => {
    const signer = library.getSigner();
    const zeroPaddedEventHash = "0x" + eventHash
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      let calldata = bridge.interface.encodeFunctionData("unpendingWithdraw", [zeroPaddedEventHash])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (e) {
      console.log(e.Message)
    }
  }

  return (
    
    <div className="flex flex-col">
      <input 
        type="text" 
        placeholder="Account Id" 
        value={accountId}
        onChange={(evt) => setAccountId(evt.target.value)}
        className="input w-full max-w-xs"
      />
      <input 
        type="text" 
        placeholder="Account Number" 
        value={accountId}
        onChange={(evt) => setAccountNumber(evt.target.value)}
        className="input w-full max-w-xs"
      />
      <input 
        type="text" 
        placeholder="Block Height" 
        value={accountId}
        onChange={(evt) => setBlockHeight(evt.target.value)}
        className="input w-full max-w-xs"
      />
      <button type="button" className="btn btn-outline btn-accent" onClick={() => {withdrawBySnapshot()}}>
        Withdraw By Snapshot (Emergency)
      </button>
      <button className="btn">
        {data?.name}
        <div className="ml-2 badge">{data?.symbol}</div>
        <div className="ml-2 badge badge-info">{data?.decimals}</div>
      </button>
      <div className="shadow stats">
        <div className="stat">
          <div className="stat-title">Postchain Balance</div>
          <div className="stat-value">{Number(formatUnits(data?.balance ?? 0, data?.decimals)).toFixed(6)}</div>
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="table w-full">
          <thead>
            <tr>
              <th>Serial</th>
              <th>{tokenType === "ERC20" ? 'Amount' : 'Token ID'}</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {data?.withdraws?.map((w) => {
              const eventHash = tokenType === 'ERC20' ? calculateEventLeafHash(w.serial, chainId, w.token, w.beneficiary, w.amount)
                : calculateEventLeafHash(w.serial, chainId, w.token, w.beneficiary, tokenId)
              return (<tr key={w?.serial}>
                <th>{w?.serial}</th>
                <td>{tokenType === "ERC20" ? Number(formatUnits(w?.amount.toString() ?? 0, data?.decimals)).toFixed(6) : tokenId}</td>
                <td>
                  <button type="button" className="btn btn-outline btn-accent" onClick={() => withdrawRequest(eventHash)}>
                    Withdraw Request
                  </button>
                  <button type="button" className="btn btn-outline btn-accent" onClick={() => withdraw(eventHash)}>
                    Withdraw
                  </button>
                  <button type="button" className="btn btn-outline btn-accent" onClick={() => withdrawToPostchain(eventHash)}>
                    Withdraw To Postchain
                  </button>                  
                  <button type="button" className="btn btn-outline btn-accent" onClick={() => pending(eventHash)}>
                    Pending
                  </button>
                  <button type="button" className="btn btn-outline btn-accent" onClick={() => unpending(eventHash)}>
                    Unpending
                  </button>
                </td>
              </tr>)
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}

const Bridge = ({ bridgeAddress, tokenAddress }: Props) => {
  const { library, chainId, account } = useWeb3React()
  const [balance, setBalance] = useState(BigNumber.from(0))
  const [deposit, setDeposit] = useState(BigNumber.from(0))
  const [amount, setAmount] = useState(0)
  const [height, setHeight] = useState("")
  const [blockRid, setBlockRid] = useState("")
  const [assetId, setAssetId] = useState("")
  const [accountId, setAccountId] = useState("")
  const [authDescId, setAuthDescId] = useState("")
  const [withdrawAmount, setWithdrawAmount] = useState(0)
  const [unit, setUnit] = useState(18)
  const tokenId = 380
  const userPUB = Buffer.from(
    "038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8",
    "hex"
  );
  const userPRIV = Buffer.from(
    "71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825",
    "hex"
  );
  const adminPUB = Buffer.from(
    "02a829e1d7fffbd856a04b53ec7d478d8896803b571c7700ec464d6a9d4f0e3bbd",
    "hex"
  );
  const adminPRIV = Buffer.from(
    "2e88348d2b7aa474be72eab4ea5613e7a39e9c27078b00a2cdc3ce94d912aeb9",
    "hex"
  );
  var tokenType: string
  if (tokenAddress === "0x932Ca55B9Ef0b3094E8Fa82435b3b4c50d713043") {
    tokenType = "ERC721"
  } else {
    tokenType = "ERC20"
  }

  const waitConfirmation = function (txRID) {
    return new Promise((resolve, reject) => {
      rest.status(txRID, (err, res) => {
        if (err) {
          resolve(err);
        } else {
          const status = res.status;
          switch (status) {
            case "confirmed":
              resolve(null)
              break;
            case "rejected":
              reject(Error("Message was rejected"))
              break
            case "unknown":
              reject(Error("Server lost our message"))
              break
            case "waiting":
              setTimeout(() => waitConfirmation(txRID).then(resolve, reject), 100)
              break
            default:
              console.log(status)
              reject(Error("got unexpected response from server"))
          }
        }
      })
    })
  }

  const postchainRegisterEVMAccount = async () => {
    try {
      const messageTemplate = "Create account for EVM wallet:\n{1}\n\nDisposable key:\n{2}"
      const evmKey = account.slice(2) || "" // Remove '0x'
      const message = messageTemplate
                        .replace("{1}", evmKey.toLowerCase())
                        .replace("{2}", userPUB.toString("hex"))
      var tx = client.newTransaction([userPUB])
      const signer = library.getSigner()
      var sig: string = await signer.signMessage(message);
      sig = sig.slice(2)
      var r = sig.slice(0, 64)
      var s = sig.slice(64, 128)
      var v = parseInt(sig.slice(128, 130), 16)
      const signature: Signature = [
        r,
        s,
        v
      ]
      tx.addOperation("ft3.evm.register_account", evmKey.toLowerCase(), createAuthDesc(userPUB.toString("hex")), signature)
      tx.addOperation("nop", Date.now())
      tx.sign(userPRIV, userPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainRegisterAccount = async () => {
    try {
      var tx = client.newTransaction([userPUB])
      tx.addOperation("register_user_account", userPUB)
      tx.addOperation("nop", Date.now())
      tx.sign(userPRIV, userPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainRegisterAdminAccount = async () => {
    try {
      var tx = client.newTransaction([adminPUB])
      tx.addOperation("register_admin_account")
      tx.addOperation("nop", Date.now())
      tx.sign(adminPRIV, adminPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainRegisterChromiaBaseOriginals =async () => {
    try {
      var tx = client.newTransaction([adminPUB])
      tx.addOperation("register_chromia_base_originals")
      tx.addOperation("nop", Date.now())
      tx.sign(adminPRIV, adminPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainInitEifOriginalInterface =async () => {
    try {
      var tx = client.newTransaction([adminPUB])
      tx.addOperation("init_eif_original_interface")
      tx.addOperation("nop", Date.now())
      tx.sign(adminPRIV, adminPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainAddEifNftMapping = async () => {
    try {
      var tx = client.newTransaction([adminPUB])
      tx.addOperation("add_eif_nft_mapping", chainId, tokenAddress.toLowerCase())
      tx.addOperation("nop", Date.now())
      tx.sign(adminPRIV, adminPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainCreateAsset = async () => {
    try {
      var tokenContract = new ethers.Contract(tokenAddress, ERC20TokenArtifacts.abi, library)
      let name = await tokenContract.name()
      var tx = client.newTransaction([adminPUB])
      tx.addOperation("ft3.dev_register_asset", name, Buffer.from(blockchainRID, 'hex'))
      tx.addOperation("nop", Date.now())
      tx.sign(adminPRIV, adminPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainAddTokenMapping = async () => {
    try {
      var tx = client.newTransaction([adminPUB])
      tx.addOperation("add_new_token_mapping", chainId, tokenAddress.toLowerCase(), Buffer.from(assetId, 'hex'))
      tx.addOperation("nop", Date.now())
      tx.sign(adminPRIV, adminPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainRegisterERC20Token = async () => {
    try {
      var tokenContract = new ethers.Contract(tokenAddress, ERC20TokenArtifacts.abi, library)
      let name: string = await tokenContract.name()
      let symbol: string = await tokenContract.symbol()
      let decimals: number = await tokenContract.decimals()
      var tx = client.newTransaction([adminPUB])
      tx.addOperation("add_new_evm_erc20", chainId, tokenAddress.toLowerCase(), name, symbol, decimals)
      tx.addOperation("nop", Date.now())
      tx.sign(adminPRIV, adminPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainWithdraw = async () => {
    try {
      var tx = client.newTransaction([userPUB])
      const signer = library.getSigner()
      const pk = util.toBuffer(userPUB).toString('hex')
      var signature = await signer.signMessage(pk);
      signature = signature.split('x')[1];
      var r = Buffer.from(signature.substring(0, 64), 'hex')
      var s = Buffer.from(signature.substring(64, 128), 'hex')
      var v = parseInt(signature.substring(128, 130), 16) - 27

      if (tokenType === "ERC721") {
        tx.addOperation("withdraw_ERC721", chainId, tokenAddress.toLowerCase(), account.toLowerCase(), tokenId, r, s, v, pk)
      } else {
        const amount = ethers.BigNumber.from(withdrawAmount).mul(ethers.BigNumber.from(10).pow(unit)).toString()
        tx.addOperation("withdraw_ERC20", chainId, tokenAddress.toLowerCase(), account.toLowerCase(), parseInt(amount), r, s, v, pk)
      }
      tx.addOperation("nop", Date.now())
      tx.sign(userPRIV, userPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainClaim = async () => {
    try {
      var tx = client.newTransaction([userPUB])
      const signer = library.getSigner()
      const pk = util.toBuffer(userPUB).toString('hex')
      var signature = await signer.signMessage(pk);
      signature = signature.split('x')[1];
      var r = Buffer.from(signature.substring(0, 64), 'hex')
      var s = Buffer.from(signature.substring(64, 128), 'hex')
      var v = parseInt(signature.substring(128, 130), 16) - 27

      if (tokenType === "ERC721") {
        tx.addOperation("claim_ERC721", chainId, tokenAddress.toLowerCase(), account.toLowerCase(), tokenId, r, s, v, pk, Buffer.from(accountId, "hex"))
      } else {
        const amount = ethers.BigNumber.from(withdrawAmount).mul(ethers.BigNumber.from(10).pow(unit)).toString()
        tx.addOperation("claim_ERC20", chainId, tokenAddress.toLowerCase(), account.toLowerCase(), parseInt(amount), r, s, v, pk, Buffer.from(accountId, "hex"))
      }
      tx.addOperation("nop", Date.now())
      tx.sign(userPRIV, userPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainDeposit = async () => {
    try {
      var tx = client.newTransaction([userPUB])
      const auth = [Buffer.from(accountId, 'hex'), Buffer.from(authDescId, 'hex')]
      if (tokenType === "ERC721") {
        tx.addOperation("deposit_non_fungible_original", auth, Buffer.from(assetId, 'hex'), chainId, tokenAddress.toLowerCase(), account.toLowerCase())
      } else {
        const amount = ethers.BigNumber.from(withdrawAmount).mul(ethers.BigNumber.from(10).pow(unit)).toString()
        tx.addOperation("deposit_ft3_token", auth, chainId, tokenAddress.toLowerCase(), account.toLowerCase(), parseInt(amount))
      }
      tx.addOperation("nop", Date.now())
      tx.sign(userPRIV, userPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }

  const postchainBridgeToEVM = async () => {
    try {
      var tx = client.newTransaction([userPUB])
      const auth = [Buffer.from(accountId, 'hex'), Buffer.from(authDescId, 'hex')]
      if (tokenType === "ERC721") {
        tx.addOperation("bridge_non_fungible_original_to_evm", auth, Buffer.from(assetId, 'hex'), chainId, tokenAddress.toLowerCase(), account.toLowerCase())
      } else {
        const amount = ethers.BigNumber.from(withdrawAmount).mul(ethers.BigNumber.from(10).pow(unit)).toString()
        tx.addOperation("bridge_ft3_token_to_evm", auth, chainId, tokenAddress.toLowerCase(), account.toLowerCase(), parseInt(amount))
      }
      tx.addOperation("nop", Date.now())
      tx.sign(userPRIV, userPUB)
      let txRID = tx.getTxRID()
      tx.send((err) => {
        if (err !== null) {
          console.log(err)
          return
        }
        toast.promise(waitConfirmation(txRID), {
          loading: `Transaction submitted. Wait for confirmation...`,
          success: <b>Transaction confirmed!</b>,
          error: <b>Transaction failed!.</b>,
        })
      })
    } catch (error) {
      console.log(error)
    }
  }  

  useEffect(() => {
    const fetchDepositedTokenInfo = () => {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )

      var tokenContract;
      if (tokenType === "ERC721") {
        tokenContract = new ethers.Contract(
          tokenAddress,
          ERC721TokenArtifacts.abi,
          library
        )
        tokenContract.balanceOf(account).then(setBalance).catch()
        setUnit(0)
        bridge._owners(tokenAddress, tokenId).then((owner: string) => {
          if (owner === account) {
            setDeposit(BigNumber.from(1))
          }
        }).catch()
      } else {
        tokenContract = new ethers.Contract(
          tokenAddress,
          ERC20TokenArtifacts.abi,
          library
        )
        tokenContract.balanceOf(account).then(setBalance).catch()
        tokenContract.decimals().then(setUnit).catch()
        bridge._balances(tokenAddress).then(setDeposit).catch()
      }
    };
    try {
      fetchDepositedTokenInfo();
    } catch (error) {
    }
  }, [library, tokenAddress, account]);

  const triggerMassExit = async () => {
    const signer = library.getSigner()
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      const calldata = bridge.interface.encodeFunctionData("triggerMassExit", [BigNumber.from(height), Buffer.from(blockRid, "hex")])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
      console.log(error)
    }
  }

  const allowToken = async () => {
    const signer = library.getSigner()
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      const calldata = bridge.interface.encodeFunctionData("allowToken", [tokenAddress])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
      console.log(error)
    }
  }

  const allowNFT = async () => {
    const signer = library.getSigner()
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      const calldata = bridge.interface.encodeFunctionData("allowNFT", [tokenAddress])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
      console.log(error)
    }
  }

  const fundTokens = async () => {
    const signer = library.getSigner()
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      const value = ethers.BigNumber.from(amount).mul(ethers.BigNumber.from(10).pow(unit))
      const calldata = bridge.interface.encodeFunctionData("fund", [tokenAddress, value])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
      console.log(error)
    }
  };

  const depositTokens = async () => {
    const signer = library.getSigner()
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      const value = ethers.BigNumber.from(amount).mul(ethers.BigNumber.from(10).pow(unit))
      const calldata = bridge.interface.encodeFunctionData("deposit", [tokenAddress, value, Buffer.from(accountId, 'hex')])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
      console.log(error)
    }
  };

  const fundNFTokens = async () => {
    const signer = library.getSigner()
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      const id = ethers.BigNumber.from(tokenId)
      const calldata = bridge.interface.encodeFunctionData("fundNFT", [tokenAddress, id])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
    }
  };

  const depositNFTokens = async () => {
    const signer = library.getSigner()
    try {
      const bridge = new ethers.Contract(
        bridgeAddress,
        BridgeArtifacts.abi,
        library
      )
      const id = ethers.BigNumber.from(tokenId)
      const calldata = bridge.interface.encodeFunctionData("depositNFT", [tokenAddress, id, Buffer.from(accountId, 'hex')])
      await sendTnx(signer, bridgeAddress, calldata)
    } catch (error) {
    }
  };

  const approveTokens = async () => {
    const signer = library.getSigner();
    try {
      const tokenContract = new ethers.Contract(tokenAddress, ERC20TokenArtifacts.abi, library)
      const value = ethers.BigNumber.from(amount).mul(ethers.BigNumber.from(10).pow(unit))
      const calldata = tokenContract.interface.encodeFunctionData("approve", [bridgeAddress, value])
      await sendTnx(signer, tokenAddress, calldata)
    } catch (error) {
    }
  };

  const setApprovalForAll = async () => {
    const signer = library.getSigner();
    try {
      const tokenContract = new ethers.Contract(tokenAddress, ERC721TokenArtifacts.abi, library)
      const calldata = tokenContract.interface.encodeFunctionData("setApprovalForAll", [bridgeAddress, true])
      await sendTnx(signer, tokenAddress, calldata)
    } catch (error) {
    }
  }

  return (
    <div className="relative py-3 sm:max-w-5xl sm:mx-auto">
      {chainId !== 5 && chainId !== 97 && chainId !== 80001 && (
        <>
          <div className="alert">
            <div className="flex-1">
              <svg
                xmlns="http://www.w3.org/2000/svg"
                fill="none"
                viewBox="0 0 24 24"
                stroke="#ff5722"
                className="w-6 h-6 mx-2"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636"
                />
              </svg>
              <label>Please connect to the Polygon Mumbai/Görli/BSC testnet for testing.</label>
            </div>
          </div>
          <div className="divider"></div>
        </>
      )}

      <div className="flex items-center w-full px-4 py-10 bg-cover card bg-base-200">
        <TokenInfo tokenAddress={tokenAddress} bridgeAddress={bridgeAddress} tokenType={tokenType} tokenId={tokenId} />

        <div className="text-center shadow-2xl card">
          <div className="card-body">
            {/* <h2 className="card-title">ERC20 Token Deposit</h2> */}
            <div className="shadow stats">
              <div className="stat">
                <div className="stat-title">Balance</div>
                <div className="stat-value">{Number(formatUnits(balance, unit)).toFixed(6)}</div>
              </div>
              <div className="stat">
                <div className="stat-title">Deposited Ammount</div>
                <div className="stat-value">{Number(formatUnits(deposit, unit)).toFixed(6)}</div>
              </div>
              <div className="stat">
                <div className="stat-title">New Deposit</div>
                <div className="stat-value">{amount}</div>
              </div>
            </div>

            <input 
              type="text" 
              placeholder="Account Id" 
              value={accountId}
              onChange={(evt) => setAccountId(evt.target.value)}
              className="input w-full max-w-xs"
            />
            <input 
              type="text" 
              placeholder="Auth Description Id" 
              value={authDescId}
              onChange={(evt) => setAuthDescId(evt.target.value)}
              className="input w-full max-w-xs"
            />
            <input 
              type="text" 
              placeholder="Asset Id" 
              value={assetId}
              onChange={(evt) => setAssetId(evt.target.value)}
              className="input w-full max-w-xs"
            />
            <div>
              <div className="justify-center card-actions">
                <button onClick={postchainRegisterAccount} type="button" className="btn btn-outline btn-accent">
                  Register User Account
                </button>
                <button onClick={postchainRegisterEVMAccount} type="button" className="btn btn-outline btn-accent">
                  Register EVM Account
                </button>
                <button onClick={postchainRegisterAdminAccount} type="button" className="btn btn-outline btn-accent">
                  Register Admin Account
                </button>
                <button onClick={postchainCreateAsset} type="button" className="btn btn-outline btn-accent">
                  Create New Asset
                </button>
                <button onClick={postchainRegisterERC20Token} type="button" className="btn btn-outline btn-accent">
                  Register ERC20 Token
                </button>
                <button onClick={postchainAddTokenMapping} type="button" className="btn btn-outline btn-accent">
                  Add Token Mapping
                </button>
              </div>
            </div>

            <input 
              type="text" 
              placeholder="Mass Exit Block Height" 
              value={height}
              onChange={(evt) => setHeight(evt.target.value)}
              className="input w-full max-w-xs"
            />
            <input 
              type="text" 
              placeholder="Mass Exit Block Rid" 
              value={blockRid}
              onChange={(evt) => setBlockRid(evt.target.value)}
              className="input w-full max-w-xs"
            />
            <div>
              <div className="justify-center card-actions">
                <button onClick={triggerMassExit} type="button" className="btn btn-outline btn-accent">
                  Mass Exit (Emergency Only)
                </button>
              </div>
            </div>

            <div>
              <div className="justify-center card-actions">
                <button onClick={postchainRegisterChromiaBaseOriginals} type="button" className="btn btn-outline btn-accent">
                  Register Chromia Base Originals
                </button>
                <button onClick={postchainInitEifOriginalInterface} type="button" className="btn btn-outline btn-accent">
                  Init Eif Original Interface
                </button>
                <button onClick={postchainAddEifNftMapping} type="button" className="btn btn-outline btn-accent">
                  Add Eif Nft Mapping
                </button>
              </div>
            </div>

            <input
              type="range"
              max="10"
              value={amount}
              onChange={(evt) => setAmount(evt.target.valueAsNumber)}
              className="range range-accent"
            />
            {tokenType === "ERC20" && (
              <>
                <div>
                  <div className="justify-center card-actions">
                    <button onClick={approveTokens} type="button" className="btn btn-outline btn-accent">
                      Approve
                    </button>
                    <button onClick={allowToken} type="button" className="btn btn-outline btn-accent">
                      Allow Token
                    </button>
                    <button onClick={depositTokens} type="button" className="btn btn-outline btn-accent">
                      Deposit
                    </button>
                    <button onClick={fundTokens} type="button" className="btn btn-outline btn-accent">
                      Fund
                    </button>
                  </div>
                </div>
              </>
            )}
            {tokenType === "ERC721" && (
              <>
                <div>
                  <div className="justify-center card-actions">
                    <button onClick={setApprovalForAll} type="button" className="btn btn-outline btn-accent">
                      Approve
                    </button>
                    <button onClick={allowNFT} type="button" className="btn btn-outline btn-accent">
                      Allow NFT
                    </button>
                    <button onClick={depositNFTokens} type="button" className="btn btn-outline btn-accent">
                      Deposit
                    </button>
                    <button onClick={fundNFTokens} type="button" className="btn btn-outline btn-accent">
                      Fund
                    </button>                    
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
        <div className="divider"></div>


        <div className="text-center shadow-2xl card"><div className="card-body">
          <div className="shadow stats">
            <div className="stat">
              <div className="stat-title">Amount</div>
              <div className="stat-value">{withdrawAmount}</div>
            </div>
          </div>
          <div className="form-control">
            <input type="range" max="10" className="range range-accent" value={withdrawAmount}
              onChange={(evt) => setWithdrawAmount(evt.target.valueAsNumber)} />
          </div>
          <div className="justify-center card-actions">
            <button onClick={postchainWithdraw} type="button" className="btn btn-outline btn-accent">
              Withdraw on Postchain
            </button>
            <button onClick={postchainDeposit} type="button" className="btn btn-outline btn-accent">
              Deposit on Postchain
            </button>
            <button onClick={postchainClaim} type="button" className="btn btn-outline btn-accent">
              Claim on Postchain
            </button>
            <button onClick={postchainBridgeToEVM} type="button" className="btn btn-outline btn-accent">
              Bridge to EVM
            </button>
          </div>
        </div></div>
        <div className="divider"></div>
      </div>
    </div>
  );
};

export default Bridge;