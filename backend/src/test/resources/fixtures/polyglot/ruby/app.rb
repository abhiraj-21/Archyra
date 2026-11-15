require_relative 'lib/services/user_service'

def run
  service = App::Services::UserService.new(nil)
  service.load(1)
end
