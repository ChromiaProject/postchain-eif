import { Flag, HexString } from "./types";

export const createAuthDesc = (pubkey: HexString, flags: Flag[] = [Flag.TRANSFER]) => {
    return [
        "S",
        [pubkey],
        [flags, pubkey],
        null,
    ]
}