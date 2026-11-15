import { Database } from './db';
const fs = require('fs');

export class UserStore {
  constructor() {
    this.db = new Database('memory');
  }

  find(id) {
    fs.readFileSync('x');
    return this.db.query('select ' + id);
  }
}
