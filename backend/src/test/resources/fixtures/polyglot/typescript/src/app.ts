import { createUserService } from './services/user.service';
import { HttpUserRepository } from './services/user.repository';

export function main(http: any) {
  const service = createUserService(new HttpUserRepository(http));
  service.load('1');
}
