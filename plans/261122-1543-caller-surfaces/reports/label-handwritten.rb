#!/usr/bin/env ruby
# One-off for phase 3: adds x-allowed-callers and the generated "Callers / Requires" line to the three hand-written
# OpenAPI documents, preserving every hand-written word. Dry-run by default; --write applies.
require 'yaml'

ROOT = File.expand_path('../../..', __dir__)
WRITE = ARGV.include?('--write')

CATALOG = {}
Dir[File.join(ROOT, 'contracts/permissions/*.yaml')].sort.each do |file|
  YAML.load_file(file).each { |entry| CATALOG[entry['code']] = entry }
end

ORDER = %w[platform-user tenant-user tenant-system customer-session public provider internal]

def callers_for(code)
  entry = CATALOG[code]
  raise "unknown code #{code}" unless entry

  scopes = Array(entry['scopes']).map(&:upcase)
  callers = []
  callers << 'platform-user' if scopes.include?('PLATFORM')
  callers << 'tenant-user' if scopes.include?('TENANT')
  callers << 'tenant-system' if entry['grantable_to_tenant_system']
  callers << 'internal' if scopes.empty?
  ORDER.select { |caller| callers.include?(caller) }
end

def line(callers, code)
  code ? "Callers: #{callers.join(', ')}. Requires `#{code}`." : "Callers: #{callers.join(', ')}."
end

files = {
  'booking' => 'contracts/openapi/booking.yaml',
  'customer' => 'contracts/openapi/customer.yaml',
  'loyalty' => 'contracts/openapi/loyalty.yaml'
}
problems = []

files.each do |app, relative|
  file = File.join(ROOT, relative)
  doc = YAML.load_file(file)
  operations = {}
  doc['paths'].each do |path, item|
    item.each do |method, operation|
      next unless operation.is_a?(Hash) && operation['operationId']

      code = operation['x-required-permission']
      callers = if code
                  callers_for(code)
                elsif path.start_with?('/p')
                  ['public']
                else
                  problems << "#{relative}: #{method.upcase} #{path} names no permission and is not public"
                  []
                end
      operations[[path, method]] = { callers: callers, code: code, line: line(callers, code) }
    end
  end

  lines = File.readlines(file)
  blocks = []
  in_paths = false
  current_path = nil
  lines.each_with_index do |text, index|
    break if index.positive? && in_paths && text =~ /^\S/ && text !~ /^paths:/

    in_paths = true if text =~ /^paths:\s*$/
    next unless in_paths

    current_path = Regexp.last_match(1) if text =~ /^  (\/[^:]+):\s*$/
    blocks << { path: current_path, method: Regexp.last_match(1), start: index } if text =~ /^    (get|post|put|patch|delete):\s*$/
  end

  blocks.each do |block|
    block[:end] = (block[:start] + 1...lines.size).find { |i| lines[i] =~ /^ {0,4}\S/ } || lines.size
  end

  changed = 0
  blocks.reverse_each do |block|
    key = [block[:path], block[:method]]
    info = operations[key]
    unless info
      problems << "#{relative}: no parsed operation for #{block[:method].upcase} #{block[:path]}"
      next
    end
    body = lines[block[:start]...block[:end]]
    next if info[:callers].empty?

    label_present = body.any? { |text| text =~ /^      x-allowed-callers:/ }
    description_index = body.index { |text| text =~ /^      description: / }
    line_present = description_index && body[description_index].include?('Callers:')

    if description_index && !line_present
      description = body[description_index].rstrip
      if description.start_with?('      description: "') && description.end_with?('"')
        lines[block[:start] + description_index] = description[0...-1] + "\\n\\n#{info[:line]}\"\n"
        changed += 1
      else
        problems << "#{relative}: unsupported description style at #{block[:method].upcase} #{block[:path]}"
      end
    end

    insertion = +''
    insertion << "      description: \"#{info[:line]}\"\n" unless description_index
    unless label_present
      insertion << "      x-allowed-callers:\n"
      info[:callers].each { |caller| insertion << "      - #{caller}\n" }
    end
    unless insertion.empty?
      lines.insert(block[:end], insertion)
      changed += 1
    end
  end

  puts "#{relative}: #{operations.size} operations, #{changed} changes"
  File.write(file, lines.join) if WRITE && changed.positive?
end

puts 'PROBLEMS:'
problems.uniq.each { |problem| puts "  #{problem}" }
