mod model;
mod service;

use crate::service::server::Server;
use crate::model::user::User;

fn main() {
    let server = Server::new();
    server.start(User::new("a"));
}
