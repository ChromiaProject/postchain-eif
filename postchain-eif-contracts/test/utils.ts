import { ethers } from "hardhat";
import { BytesLike } from "ethers";

export function strip0x(hexString: string): string {
    return hexString.startsWith("0x") ? hexString.slice(2) : hexString;
}

export function toHex(str: string): string {
    return str.startsWith("0x") ? str : "0x" + str;
}

export function postchainMerkleNodeHash(values: any[]): string {
    return ethers.solidityPackedSha256(['uint8', 'bytes32', 'bytes32'], values)
}

export function hashGtvNullLeaf(): string {
    return ethers.solidityPackedSha256(['uint8', 'uint8', 'uint8', 'uint8', 'uint8'], [0x1, 0xa0, 0x02, 0x05, 0x00])
}

export function hashGtvBytes32Leaf (data: BytesLike): string {
    return ethers.solidityPackedSha256(['uint8', 'uint8', 'uint8', 'uint8', 'uint8', 'bytes32'], [0x1, 0xA1, 32+2, 0x4, 32, data])
}

export function hashGtvBytes64Leaf (data: BytesLike): string {
    return ethers.solidityPackedSha256(['uint8', 'uint8', 'uint8', 'uint8', 'uint8', 'bytes'], [0x1, 0xA1, 64+2, 0x4, 64, data])
}

export var hashGtvIntegerLeaf = function (num: number): string {
    let nbytes = 1
    let remainingValue = Math.trunc(num / 256)
    while (remainingValue > 0) {
        nbytes += 1
        remainingValue = Math.trunc(remainingValue / 256)
    }
    let b = new Uint8Array(nbytes)
    remainingValue = num
    for (let i = 1; i <= nbytes; i++) {
        let v = remainingValue & 0xFF
        b[nbytes - i] = v
        remainingValue = Math.trunc(remainingValue / 256)
    }
    if ((b[0] & 0x80) > 0) {
        nbytes += 1
        let a = new Uint8Array(1)
        a[0] = 0
        return ethers.solidityPackedSha256(['uint8', 'uint8', 'uint8', 'uint8', 'uint8', 'bytes'], [0x1, 0xA3, nbytes+2, 0x2, nbytes, new Uint8Array([...a, ...b])])
    }
    return ethers.solidityPackedSha256(['uint8', 'uint8', 'uint8', 'uint8', 'uint8', 'bytes'], [0x1, 0xA3, nbytes+2, 0x2, nbytes, b])
}

export function hashGtvStringLeaf(text: string): string {
    return ethers.solidityPackedSha256(['uint8', 'uint8', 'uint8', 'uint8', 'uint8', 'string'], [0x1, 0xA2, text.length + 2, 0xC, text.length, text])
}
