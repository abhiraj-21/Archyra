import { HttpClient } from '@angular/common/http';
import { User } from '../models/user';

export interface UserRepository {
  find(id: string): Promise<User>;
}

export class HttpUserRepository implements UserRepository {
  constructor(private http: HttpClient) {}

  async find(id: string): Promise<User> {
    return this.http.get<User>('/users/' + id);
  }
}
