export class Database {
  constructor(url) {
    this.url = url;
  }

  query(sql) {
    return [sql];
  }
}
