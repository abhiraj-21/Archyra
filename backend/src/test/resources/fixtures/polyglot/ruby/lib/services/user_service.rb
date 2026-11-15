require 'json'
require_relative '../models/user'
require_relative '../cache'
require_relative 'base_service'
require_relative 'loggable'

module App
  module Services
    class UserService < BaseService
      include Loggable

      def initialize(repo)
        @repo = repo
        @cache = Cache.new
      end

      def load(id)
        user = @repo.find(id)
        created = Models::User.new("a")
        created.display
        audit(user)
        self.audit(user)
        log("x")
        record("y")
        @cache.get(id)
        JSON.generate(id)
        user
      end

      def audit(user)
        user
      end
    end
  end
end
