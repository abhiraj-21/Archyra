export interface User {
  id: string;
  name: string;
}

export class UserImpl implements User {
  constructor(public id: string, public name: string) {}
}
