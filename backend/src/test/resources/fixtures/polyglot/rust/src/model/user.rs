use serde::Serialize;

#[derive(Serialize)]
pub struct User {
    pub name: String,
}

impl User {
    pub fn new(name: &str) -> Self {
        User { name: name.to_string() }
    }

    pub fn display(&self) -> String {
        self.name.clone()
    }
}
