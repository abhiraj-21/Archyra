use crate::model::user::User;

pub trait Base {}

pub trait Repo: Base {
    fn save(&self, user: &User) -> bool;
}

pub struct MemoryRepo {
    pub items: Vec<User>,
}

impl Repo for MemoryRepo {
    fn save(&self, user: &User) -> bool {
        true
    }
}
