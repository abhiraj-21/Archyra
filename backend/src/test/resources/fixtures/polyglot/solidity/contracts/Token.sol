// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

import "./IToken.sol";
import {Math} from "./lib/Math.sol";
import "./IVault.sol";
import "./Base.sol";
import "@openzeppelin/contracts/access/Ownable.sol";

contract Token is IToken, Ownable, Base {
    struct Holder {
        address who;
        uint256 balance;
    }

    IVault public vault;

    constructor(address v) {
        vault = IVault(v);
    }

    function transfer(address to, uint256 amount) public returns (bool) {
        vault.deposit(amount);
        uint256 s = Math.add(1, 2);
        _mint(to, amount);
        super.hello();
        Token t = new Token(to);
        t.hello();
        return true;
    }

    function _mint(address a, uint256 b) internal {}
}
