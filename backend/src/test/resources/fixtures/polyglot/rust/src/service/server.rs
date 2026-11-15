use std::collections::HashMap;
use super::repo::{MemoryRepo, Repo};
use crate::model::user::User;

pub struct Server {
    repo: Box<dyn Repo>,
    cache: HashMap<String, User>,
}

impl Server {
    pub fn new() -> Self {
        Server { repo: Box::new(MemoryRepo { items: vec![] }), cache: HashMap::new() }
    }

    pub fn start(&self, user: User) {
        self.repo.save(&user);
        user.display();
        self.log();
        helper(1);
    }

    fn log(&self) {}
}

fn helper(n: i32) {}
