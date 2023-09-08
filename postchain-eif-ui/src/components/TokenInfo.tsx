import React from 'react';
import { ethers } from "ethers";
import { useState } from "react";
import { useQuery } from "react-query";
import { toast } from "react-hot-toast";
import { useWeb3React } from "@web3-react/core";
import { formatUnits } from "@ethersproject/units";
import { hexValue, hexZeroPad, keccak256 } from "ethers/lib/utils";
import { restClient, gtxClient } from "postchain-client";

import ERC20TokenArtifacts from "../postchain-eif-contracts/artifacts/@openzeppelin/contracts/token/ERC20/ERC20.sol/ERC20.json";
import ERC721TokenArtifacts from "../postchain-eif-contracts/artifacts/@openzeppelin/contracts/token/ERC721/ERC721.sol/ERC721.json";
import BridgeArtifacts from "../postchain-eif-contracts/artifacts/contracts/TokenBridge.sol/TokenBridge.json";

const postchainURL = process.env.REACT_APP_POSTCHAIN_URL || ""
const blockchainRID = process.env.REACT_APP_POSTCHAIN_BRID || ""
const rest = restClient.createRestClient([ postchainURL ], blockchainRID, 5);
const client = gtxClient.createClient(
  rest,
  blockchainRID,
  []
)

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

interface TokenInfoProps {
  tokenAddress: string;
  bridgeAddress: string;
  tokenType: string;
  tokenId: number 
}

export const TokenInfo = ({ tokenAddress, bridgeAddress, tokenType, tokenId }: TokenInfoProps) => {
  const { library, chainId, account } = useWeb3React();
  const [accountId, setAccountId] = useState("")
  const [accountNUmber, setAccountNumber] = useState("")
  const [blockHeight, setBlockHeight] = useState("")
  // Remove 0x to pass address like byte_array
  let token_address = tokenAddress.slice(2) || ""
  let beneficiary = account?.slice(2) || ""
  
  if(typeof chainId != 'number') return null;
  
  const fetchTokenInfo = async () => {
    var tokenContract;
    let balance;
    let withdraws;
    if (tokenType === "ERC721") {
      tokenContract = new ethers.Contract(tokenAddress, ERC721TokenArtifacts.abi, library);
      const hasToken = await client.query('evm_has_erc721', { "network_id": chainId, "token_address": token_address.toLowerCase(), "beneficiary": beneficiary.toLowerCase(), "token_id": tokenId })
      balance = hasToken ? 1 : 0;
      withdraws = await client.query('get_erc721_withdrawal', {
        'network_id': chainId,
        'token_address': token_address.toLowerCase(),
        'token_id': tokenId,
        'beneficiary': beneficiary.toLowerCase()
      });
    } else {
      tokenContract = new ethers.Contract(tokenAddress, ERC20TokenArtifacts.abi, library);
      balance = await client.query('evm_balance_of_erc20', { "network_id": chainId, "token_address": token_address.toLowerCase(), "beneficiary": beneficiary.toLowerCase() })
      withdraws = await client.query('get_erc20_withdrawal', {
        'network_id': chainId,
        'token_address': token_address.toLowerCase(),
        'beneficiary': beneficiary.toLowerCase()
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
    var result: any = [];
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
        event += hexZeroPad(hexValue(arg), 32).substring(2)
      } else if (typeof arg === 'string') {
        event += hexZeroPad("0x" + arg, 32).substring(2)
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
        // @ts-ignore
        calldata = bridge.interface.encodeFunctionData("withdrawRequest", [eventData, evtProof, blockHeader, sigs, signers, extraProof])
      } else {
        // @ts-ignore
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
      let data = await client.query('get_account_state_merkle_proof', { "blockHeight": parseInt(blockHeight), "accountNumber": parseInt(accountNUmber)})
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

      const stateData = "0x" + state.stateData
      const stateProof = state.stateProof
      let merkleProofs = new Array<String>(stateProof.merkleProofs.length)
      for (let i = 0; i < stateProof.merkleProofs.length; i++) {
        merkleProofs[i] = "0x" + stateProof.merkleProofs[i]
      }
      const dataProof = {
        leaf: "0x" + stateProof.leaf,
        position: stateProof.position,
        merkleProofs: merkleProofs,
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
      // @ts-ignore
      let calldata = bridge.interface.encodeFunctionData("withdrawBySnapshot", [stateData, dataProof, blockHeader, sigs, signers, extraProof])
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
        // @ts-ignore
        calldata = bridge.interface.encodeFunctionData("withdraw", [zeroPaddedEventHash, account])
      } else {
        // @ts-ignore
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
        // @ts-ignore
        calldata = bridge.interface.encodeFunctionData("withdrawToPostchain", [zeroPaddedEventHash, Buffer.from(accountId, 'hex')])
      } else {
        // @ts-ignore
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
      // @ts-ignore
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
      // @ts-ignore
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
        value={accountNUmber}
        onChange={(evt) => setAccountNumber(evt.target.value)}
        className="input w-full max-w-xs"
      />
      
      <input 
        type="text" 
        placeholder="Block Height" 
        value={blockHeight}
        onChange={(evt) => setBlockHeight(evt.target.value)}
        className="input w-full max-w-xs"
      />
      
      <button
        type="button"
        className="btn btn-outline btn-accent"
        onClick={() => {withdrawBySnapshot()}}
      >
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
          <div className="stat-value">
            {Number(formatUnits(data?.balance ?? 0, data?.decimals)).toFixed(6)}
          </div>
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