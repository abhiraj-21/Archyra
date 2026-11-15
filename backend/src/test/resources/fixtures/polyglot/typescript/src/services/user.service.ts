import { Injectable } from '@angular/core';
import { User } from '../models/user';
import { UserRepository } from './user.repository';

@Injectable()
export class UserService {
  private cache: Map<string, User> = new Map();

  constructor(private readonly repo: UserRepository) {}

  load(id: string): Promise<User> {
    this.audit(id);
    return this.repo.find(id);
  }

  private audit(id: string): void {
    this.cache.has(id);
  }
}

export function createUserService(repo: UserRepository): UserService {
  return new UserService(repo);
}
