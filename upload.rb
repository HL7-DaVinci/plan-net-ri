require 'zip'
require 'httparty'

FHIR_SERVER = 'http://localhost:8080/plan-net/fhir'
# FHIR_SERVER = 'https://api.logicahealth.org/DVJan21CnthnPDex/open'

#FHIR_SERVER = 'https://api.logicahealth.org/DVPDexR4Payer1/open'

def upload_plan_net_resources
  file_paths = [
    File.join(__dir__, 'conformance', '*', '*.json'),
    File.join(__dir__, '..', 'pdex-plan-net-sample-data', 'output', '**', '*.json')
  ]
  filenames = file_paths.flat_map do |file_path|
    Dir.glob(file_path)
      .select { |filename| filename.end_with? '.json' }
  end
  puts "#{filenames.length} resources to upload"
  old_retry_count = filenames.length
  loop do
    filenames_to_retry = []
    filenames.each_with_index do |filename, index|
      resource = JSON.parse(File.read(filename), symbolize_names: true)
      if filename.end_with? ".transaction.json"

        patient_identifier = patient_identifier_in_transaction(resource)
        record_exists = record_exists_on_server?(patient_identifier)

        if record_exists
          puts "Patient with identifier #{patient_identifier} already exists, skipping."
        else
          response = execute_transaction(resource)
          filenames_to_retry << filename unless response.success?
        end
      else
        response = upload_resource(resource)
        filenames_to_retry << filename unless response.success?
      end

      if index % 100 == 0
        puts index
      end
    end
    break if filenames_to_retry.empty?
    retry_count = filenames_to_retry.length
    if retry_count == old_retry_count
      puts "Unable to upload #{retry_count} resources:"
      puts filenames.join("\n")
      break
    end
    puts "#{retry_count} resources to retry"
    filenames = filenames_to_retry
    old_retry_count = retry_count
  end
end

def upload_resource(resource)
  resource_type = resource[:resourceType]
  resource[:status] = 'active' if resource_type == 'SearchParameter'
  id = resource[:id]
  HTTParty.put(
    "#{FHIR_SERVER}/#{resource_type}/#{id}",
    body: resource.to_json,
    headers: { 'Content-Type': 'application/json' }
  )
end

def patient_identifier_in_transaction(transaction)

  patient_record = transaction[:entry]&.find {|r| r[:resource][:resourceType] == 'Patient'}
  identifier = patient_record[:resource][:identifier].first
  "#{identifier[:system]}|#{identifier[:value]}"
end

def record_exists_on_server?(patient_identifier)

  response = HTTParty.get(
    "#{FHIR_SERVER}/Patient",
    query: { identifier: patient_identifier },
    headers: { 'Content-Type': 'application/json' }
  )
  JSON.parse(response.body)['entry']&.any?

end

def execute_transaction(transaction)

  HTTParty.post(
    FHIR_SERVER,
    body: transaction.to_json,
    headers: { 'Content-Type': 'application/json' }
  )
end

def upload_ig_examples
    puts "Uploading ig examples..."
    definitions_url = 'https://build.fhir.org/ig/HL7/carin-bb/examples.json.zip'
    definitions_data = HTTParty.get(definitions_url, verify: false)
    definitions_file = Tempfile.new
    begin
        definitions_file.write(definitions_data)
    ensure
        definitions_file.close
    end

    Zip::File.open(definitions_file.path) do |zip_file|
        zip_file.entries
        .select { |entry| entry.name.end_with? '.json' }
        .reject { |entry| entry.name.start_with? 'ImplementationGuide' }
        .each do |entry|
            resource = JSON.parse(entry.get_input_stream.read, symbolize_names: true)
            response = upload_resource(resource)
        end
    end
    puts " ...done"
ensure
    definitions_file.unlink
end

upload_plan_net_resources
upload_ig_examples
