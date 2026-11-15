const helper = require('./db');

exports.load = function (id) {
  return prepare(id);
};

Store.prototype.save = function save(item) {
  prepare(item);
};

app.handle = (req) => {
  exports.load(req);
};

function prepare(value) {
  return value;
}
