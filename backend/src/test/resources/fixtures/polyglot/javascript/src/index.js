import express from 'express';
import { UserStore } from './userStore';

export function start() {
  const app = express();
  const store = new UserStore();
  store.find(2);
  app.listen(3000);
}
